package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaAuditPage;
import io.github.nasaruntime.saga.rc.SagaAuditRecordView;
import io.github.nasaruntime.saga.rc.SagaAuditRequest;
import io.github.nasaruntime.saga.rc.SagaGrpcStartReceipt;
import io.github.nasaruntime.saga.rc.SagaQueryPage;
import io.github.nasaruntime.saga.rc.SagaQueryRequest;
import io.github.nasaruntime.saga.rc.SagaSnapshotView;
import io.github.nasaruntime.saga.rc.SagaStartIdentity;
import io.github.nasaruntime.saga.rc.SagaStartReceipt;
import io.github.nasaruntime.saga.rc.SagaStartRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Timestamp;
import io.github.nasaruntime.saga.proto.orchestrator.v1.GetAuditTrailResponse;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaAuditRecord;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaControlState;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaSnapshot;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaStatus;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Rust gRPC control plane 到 transport 无关 {@link SagaControlPlane} 的适配器。
 *
 * <p>gRPC 使用 Rust proto 中的状态、时间窗口和分页字段；HTTP 专有的
 * {@code after_saga_id} 与空 body audit 语义不会被静默丢弃。</p>
 */
public final class GrpcSagaControlPlane implements SagaControlPlane, SagaStartSender, AutoCloseable {

    private final RustSagaClient client;

    /**
     * 业务作用：绑定已经完成 mTLS 和权限配置的 Rust gRPC client，复用其 deadline 与 trace 传播。
     *
     * @param client Rust gRPC control client
     */
    public GrpcSagaControlPlane(RustSagaClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 业务作用：通过 Rust gRPC 原子创建或幂等命中 Saga，并投影为共享 start 收据。
     *
     * @param request 固定业务身份和 definition 合同的 start 请求
     * @return 身份与本次请求一致的 Committed 或 Duplicate 收据；响应合同损坏或身份错配抛出 DATA_LOSS。
     */
    @Override
    public SagaGrpcStartReceipt start(SagaStartRequest request) {
        Objects.requireNonNull(request, "request");
        return sendStart(request, SagaStartIdentity.from(request));
    }

    /**
     * 业务作用：调用 Rust 后在响应边界内完成领域映射与 direct 身份复验。
     *
     * @param request  发送前由 Rust client 完成本地合同校验的请求
     * @param expected direct 要求的业务身份；持久化投递为空，由 dispatcher 按冻结 intent 复验
     * @return 完整且满足所需身份条件的收据；远端响应合同损坏为 DATA_LOSS。
     */
    private SagaGrpcStartReceipt sendStart(SagaStartRequest request, SagaStartIdentity expected) {
        SagaStartReceipt receipt = client.start(request);
        return SagaGrpcResponse.decode(() -> {
            SagaGrpcStartReceipt result = new SagaGrpcStartReceipt(
                    receipt.disposition(), receipt.requestDigest(), snapshot(receipt.saga()));
            // 远端允许 Duplicate 命中已有槽位；必须证明快照属于本次身份，才能对 direct 调用方确认成功。
            if (expected != null && !expected.matches(result.saga())) {
                throw new SagaProtocolException("Saga start response identity does not match request");
            }
            return result;
        });
    }

    /**
     * 业务作用：把本地冻结 JSON 的完整请求确定性映射到 Rust proto，保留 payload 原字节与全部幂等身份。
     *
     * @param body        已提交 intent 的 JSON 正文
     * @param traceparent 与 intent 同事务保存的链路上下文
     * @return 完整的 Rust Committed 或 Duplicate 收据；dispatcher 还须按冻结 intent 复验身份，异常不代表本地投递成功。
     */
    @Override
    public SagaGrpcStartReceipt startRaw(byte[] body, String traceparent) {
        return sendStart(SagaHttpStartCodec.decode(body, traceparent), null);
    }

    /**
     * 业务作用：向 reliable dispatcher 暴露实际强制 RPC 期限，禁止网络预算越过 lease。
     * 参数说明：无。
     *
     * @return 当前 Rust client 的正调用超时。
     */
    @Override
    public Duration requestTimeout() {
        return client.requestTimeout();
    }

    /**
     * 业务作用：读取 Rust gRPC 返回的权威实例快照，不在 Java 保存状态副本。
     *
     * @param tenantId    租户身份
     * @param sagaId      Saga 身份
     * @param traceparent 可选链路上下文
     * @return 身份与请求一致的 Rust 快照；远端响应损坏或身份错配抛出 DATA_LOSS。
     */
    @Override
    public SagaSnapshotView get(String tenantId, String sagaId, String traceparent) {
        SagaSnapshot response = client.get(tenantId, sagaId, traceparent);
        return SagaGrpcResponse.decode(() -> SagaReadResponse.get(snapshot(response), tenantId, sagaId));
    }

    /**
     * 业务作用：把 gRPC 支持的租户、workflow、状态、时间窗口和 opaque token 映射为共享查询页。
     *
     * @param request     查询条件
     * @param traceparent 可选链路上下文
     * @return 与请求范围和页大小一致的实例页；远端响应合同损坏或错配抛出 DATA_LOSS。
     */
    @Override
    public SagaQueryPage query(SagaQueryRequest request, String traceparent) {
        Objects.requireNonNull(request, "request");
        rejectGrpcOnlyUnsupportedQuery(request);
        int pageSize = SagaReadResponse.pageSize(request.pageSize());
        List<SagaStatus> statuses = request.statuses().stream().map(GrpcSagaControlPlane::status).toList();
        var response = client.query(
                request.tenantId(), request.workflow(), statuses,
                request.createdFromMs(), request.createdToMs(), pageSize, request.pageToken(), traceparent);
        return SagaGrpcResponse.decode(() -> SagaReadResponse.query(new SagaQueryPage(response.getSagasList().stream()
                .map(GrpcSagaControlPlane::snapshot).toList(), response.getNextPageToken()), request));
    }

    /**
     * 业务作用：读取 Rust gRPC 审计页并把 typed details 投影为 JSON 节点。
     *
     * @param request     审计目标与分页条件
     * @param traceparent 可选链路上下文
     * @return 符合页大小和 token 合同的 Rust 审计页；远端响应合同损坏抛出 DATA_LOSS。
     */
    @Override
    public SagaAuditPage audit(SagaAuditRequest request, String traceparent) {
        Objects.requireNonNull(request, "request");
        int pageSize = SagaReadResponse.pageSize(request.pageSize());
        GetAuditTrailResponse response = client.audit(
                request.tenantId(), request.sagaId(), pageSize, request.pageToken(), traceparent);
        return SagaGrpcResponse.decode(() -> SagaReadResponse.audit(new SagaAuditPage(response.getRecordsList().stream()
                .map(GrpcSagaControlPlane::auditRecord).toList(), response.getNextPageToken()), request));
    }

    /**
     * 业务作用：关闭 gRPC channel，停止后续 control plane 调用。
     */
    @Override
    public void close() {
        client.close();
    }

    /**
     * 业务作用：拒绝 gRPC 无法表达的分页条件，避免静默扩大查询范围。
     * @param request 调用方的完整查询条件
     */
    private static void rejectGrpcOnlyUnsupportedQuery(SagaQueryRequest request) {
        if (request.afterSagaId() != null) {
            throw new SagaProtocolException("gRPC control plane does not support after_saga_id");
        }
    }

    /**
     * 业务作用：把领域过滤状态映射为 gRPC 枚举，未知或未指定状态不能进入查询。
     * @param value 调用方指定的领域状态
     * @return 协议支持的状态；其它值抛出协议异常。
     */
    private static SagaStatus status(String value) {
        try {
            SagaStatus result = SagaStatus.valueOf("SAGA_STATUS_" + value);
            if (result == SagaStatus.SAGA_STATUS_UNSPECIFIED) {
                throw new IllegalArgumentException("unspecified status");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw new SagaProtocolException("query status is not supported by Rust gRPC", exception);
        }
    }

    /**
     * 业务作用：把 protobuf 快照映射为共享事实，保留字段是否存在以执行各读取 API 的合同。
     *
     * @param value Rust 返回的 protobuf 快照
     * @return 状态、身份与版本有效的快照；缺失身份、非法状态或时间时抛出协议异常。
     */
    private static SagaSnapshotView snapshot(SagaSnapshot value) {
        if (value == null || !value.hasKey()) {
            throw new SagaProtocolException("Rust gRPC Saga snapshot is incomplete");
        }
        String currentStep = value.hasCurrentStep() ? value.getCurrentStep() : null;
        Long deadlineAtMs = value.hasDeadlineAtMs() ? value.getDeadlineAtMs() : null;
        return new SagaSnapshotView(
                value.getKey().getTenantId(),
                value.getKey().getSagaId(),
                value.getWorkflow(),
                value.getDefinitionVersion(),
                value.getDefinitionDigest(),
                value.getBusinessKey(),
                statusName(value.getStatus()),
                controlStateName(value.getControlState()),
                emptyToNull(value.getDirection()),
                currentStep,
                value.getStateVersion(),
                value.getControlVersion(),
                deadlineAtMs,
                emptyToNull(value.getFailureCode()),
                emptyToNull(value.getTraceparent()),
                timestampMillis(value.hasCreatedAt() ? value.getCreatedAt() : null),
                timestampMillis(value.hasUpdatedAt() ? value.getUpdatedAt() : null));
    }

    /**
     * 业务作用：把完整的 protobuf 审计事实映射为共享投影，保留明细的开放字段。
     *
     * @param value Rust 返回的单条事实
     * @return 身份、时间与 JSON 明细均有效的记录；缺失或损坏时抛出协议异常，由响应边界转换为 DATA_LOSS。
     */
    private static SagaAuditRecordView auditRecord(
            SagaAuditRecord value) {
        JsonNode details = null;
        if (value.hasDetails()) {
            var payload = value.getDetails();
            if (!payload.getBody().isEmpty() && "application/json".equals(payload.getContentType())) {
                details = SagaEnvelopeCodec.decodeStrict(payload.getBody().toByteArray(), JsonNode.class);
            }
        }
        Timestamp occurredAt = value.hasOccurredAt() ? value.getOccurredAt() : null;
        return new SagaAuditRecordView(
                value.getAuditId(),
                value.getKind(),
                value.getStateVersion(),
                emptyToNull(value.getActor()),
                emptyToNull(value.getReason()),
                occurredAt == null ? null : SagaGrpcResponse.instant(occurredAt).toString(),
                timestampMillis(occurredAt),
                details);
    }

    /**
     * 业务作用：只向调用方暴露已识别的 Saga 状态，拒绝缺省或未知协议值。
     * @param value Rust 返回的状态枚举
     * @return 与领域合同一致的状态名；无有效状态时拒绝。
     */
    private static String statusName(SagaStatus value) {
        if (value == null || value == SagaStatus.UNRECOGNIZED
                || value == SagaStatus.SAGA_STATUS_UNSPECIFIED) {
            throw new SagaProtocolException("Rust gRPC Saga status is unspecified");
        }
        return value.name().substring("SAGA_STATUS_".length());
    }

    /**
     * 业务作用：保持管理控制态的存在性，防止未指定状态被误解为可运行。
     * @param value Rust 返回的控制态枚举
     * @return 领域控制态名称；缺失或未知时拒绝。
     */
    private static String controlStateName(SagaControlState value) {
        if (value == null || value == SagaControlState.UNRECOGNIZED
                || value == SagaControlState.SAGA_CONTROL_STATE_UNSPECIFIED) {
            throw new SagaProtocolException("Rust gRPC Saga control state is unspecified");
        }
        return value.name().substring("SAGA_CONTROL_STATE_".length());
    }

    /**
     * 业务作用：保留可选查询时间的字段存在性，并按共同 wire 合同投影已提供的时间。
     *
     * @param value 可空 protobuf 时间戳
     * @return 缺省返回 null；已存在时返回合法毫秒投影，越界时抛出协议异常。
     */
    private static Long timestampMillis(Timestamp value) {
        return value == null ? null : SagaGrpcResponse.timestampMillis(value);
    }

    /**
     * 业务作用：将 protobuf 缺省文本映射为领域可选字段，不裁剪已提供的值。
     * @param value 协议文本
     * @return 空值返回 null，非空文本原样保留。
     */
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
