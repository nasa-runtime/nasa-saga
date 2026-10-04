package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaStartReceipt;
import io.github.nasaruntime.saga.rc.SagaStartRequest;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.github.nasaruntime.saga.proto.orchestrator.v1.CapabilityDescriptor;
import io.github.nasaruntime.saga.proto.orchestrator.v1.CapabilityReceipt;
import io.github.nasaruntime.saga.proto.orchestrator.v1.DefinitionArtifact;
import io.github.nasaruntime.saga.proto.orchestrator.v1.DefinitionKey;
import io.github.nasaruntime.saga.proto.orchestrator.v1.DefinitionOperationRequest;
import io.github.nasaruntime.saga.proto.orchestrator.v1.DefinitionRecord;
import io.github.nasaruntime.saga.proto.orchestrator.v1.GetAuditTrailRequest;
import io.github.nasaruntime.saga.proto.orchestrator.v1.GetAuditTrailResponse;
import io.github.nasaruntime.saga.proto.orchestrator.v1.GetDefinitionRequest;
import io.github.nasaruntime.saga.proto.orchestrator.v1.GetSagaRequest;
import io.github.nasaruntime.saga.proto.orchestrator.v1.PublishDefinitionRequest;
import io.github.nasaruntime.saga.proto.orchestrator.v1.PublishDefinitionResponse;
import io.github.nasaruntime.saga.proto.orchestrator.v1.QuerySagasRequest;
import io.github.nasaruntime.saga.proto.orchestrator.v1.QuerySagasResponse;
import io.github.nasaruntime.saga.proto.orchestrator.v1.RegisterCapabilityRequest;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaKey;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaManagementRequest;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaOrchestratorGrpc;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaOrchestratorAdminGrpc;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaPayload;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaDefinitionRegistryGrpc;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaSnapshot;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaStatus;
import io.github.nasaruntime.saga.proto.orchestrator.v1.StartSagaRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Rust Saga Orchestrator 的 Java gRPC client。
 *
 * <p>该 client 只调用 Rust 服务端，不持有 Saga 全局状态，也不提供同名服务端实现。
 */
public final class RustSagaClient implements AutoCloseable {

    private static final Metadata.Key<String> TRACEPARENT =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;
    private final SagaOrchestratorGrpc.SagaOrchestratorBlockingStub baseStub;
    private final SagaOrchestratorAdminGrpc.SagaOrchestratorAdminBlockingStub adminStub;
    private final SagaDefinitionRegistryGrpc.SagaDefinitionRegistryBlockingStub registryStub;
    private final Duration requestTimeout;

    /**
     * 业务作用：绑定一个 mTLS channel 和有界 unary deadline，供 Java 业务调用 Rust Orchestrator。
     *
     * @param channel        已完成 TLS 与 endpoint 校验的 channel
     * @param requestTimeout 每个 RPC 的最大等待时间
     */
    public RustSagaClient(ManagedChannel channel, Duration requestTimeout) {
        if (channel == null || requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("channel and positive requestTimeout are required");
        }
        this.channel = channel;
        this.baseStub = SagaOrchestratorGrpc.newBlockingStub(channel);
        this.adminStub = SagaOrchestratorAdminGrpc.newBlockingStub(channel);
        this.registryStub = SagaDefinitionRegistryGrpc.newBlockingStub(channel);
        this.requestTimeout = requestTimeout;
    }

    /**
     * 业务作用：公开全部 unary RPC 共用的强制期限，供本地投递租约预留预算。
     * 参数说明：无。
     *
     * @return 构造时冻结的正超时。
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * 业务作用：向 Rust Orchestrator 提交 Saga start，并将 committed/duplicate 收敛为同一 Java 收据类型。
     *
     * @param request 稳定 Saga 身份、业务幂等键和首步 JSON 输入
     * @return 远端明确提交或幂等命中的快照
     */
    public SagaStartReceipt start(SagaStartRequest request) {
        request.validate();
        var builder = StartSagaRequest.newBuilder()
                .setKey(SagaKey.newBuilder()
                        .setTenantId(request.tenantId())
                        .setSagaId(request.sagaId())
                        .build())
                .setWorkflow(request.workflow())
                .setDefinitionVersion(request.definitionVersion())
                .setBusinessKey(request.businessKey())
                .setTriggerId(request.triggerId());
        if (request.expectedDefinitionDigest() != null && !request.expectedDefinitionDigest().isEmpty()) {
            builder.setExpectedDefinitionDigest(request.expectedDefinitionDigest());
        }
        if (request.input() != null || request.payload() != null) {
            SagaPayload.Builder payload = SagaPayload.newBuilder();
            if (request.payload() == null) {
                payload.setContentType("application/json")
                        .setBody(ByteString.copyFrom(SagaEnvelopeCodec.encode(request.input())));
            } else {
                payload.setContentType(request.payload().contentType())
                        .setSchemaId(request.payload().schemaId())
                        .setBody(ByteString.copyFrom(request.payload().body()));
            }
            builder.setInput(payload.build());
        }
        if (request.deadline() != null) {
            builder.setDeadline(timestamp(request.deadline()));
        }
        return SagaStartReceipt.fromProto(withTraceparent(baseStub, request.traceparent())
                .startSaga(builder.build()));
    }

    /**
     * 业务作用：读取 Rust Orchestrator 的权威 Saga 快照，供结果不明和运维查询复核最终事实。
     *
     * @param tenantId    租户身份
     * @param sagaId      Saga 身份
     * @param traceparent 可选的 W3C traceparent
     * @return Rust 持久化的快照
     */
    public SagaSnapshot get(
            String tenantId,
            String sagaId,
            String traceparent) {
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        return withTraceparent(baseStub, traceparent).getSaga(GetSagaRequest.newBuilder()
                .setKey(SagaKey.newBuilder().setTenantId(tenantId).setSagaId(sagaId).build())
                .build());
    }

    /**
     * 业务作用：按租户、workflow、状态和不透明分页 token 查询 Rust Orchestrator 实例。
     *
     * @param tenantId    授权目标租户
     * @param workflow    可选 workflow，空值表示该租户全部 workflow
     * @param statuses    状态过滤集合
     * @param pageSize    单页数量，范围 1..1000
     * @param pageToken   上一页返回的不透明 token，可为空
     * @param traceparent 可选的 W3C traceparent
     * @return Rust 返回的实例页和下一页 token
     */
    public QuerySagasResponse query(
            String tenantId,
            String workflow,
            List<SagaStatus> statuses,
            int pageSize,
            String pageToken,
            String traceparent) {
        return query(tenantId, workflow, statuses, null, null, pageSize, pageToken, traceparent);
    }

    /**
     * 业务作用：按 Rust gRPC query contract 传递状态、时间窗口和不透明分页边界，不在 Java 侧改写游标。
     *
     * @param tenantId      授权目标租户
     * @param workflow      可选 workflow，空值表示该租户全部 workflow
     * @param statuses      状态过滤集合
     * @param createdFromMs 创建时间下界，可为空
     * @param createdToMs   创建时间上界，可为空
     * @param pageSize      单页数量，范围 1..1000
     * @param pageToken     上一页返回的不透明 token，可为空
     * @param traceparent   可选的 W3C traceparent
     * @return Rust 返回的实例页和下一页 token
     */
    public QuerySagasResponse query(
            String tenantId,
            String workflow,
            List<SagaStatus> statuses,
            Long createdFromMs,
            Long createdToMs,
            int pageSize,
            String pageToken,
            String traceparent) {
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        if (pageSize < 1 || pageSize > 1000) {
            throw new SagaProtocolException("page_size must be between 1 and 1000");
        }
        if (createdFromMs != null && createdToMs != null && createdFromMs > createdToMs) {
            throw new SagaProtocolException("query time range is reversed");
        }
        var builder = QuerySagasRequest.newBuilder()
                .setTenantId(tenantId)
                .setWorkflow(workflow == null ? "" : workflow)
                .setPageSize(pageSize)
                .addAllStatuses(statuses == null ? List.of() : statuses);
        if (createdFromMs != null) {
            builder.setCreatedFromMs(createdFromMs);
        }
        if (createdToMs != null) {
            builder.setCreatedToMs(createdToMs);
        }
        if (pageToken != null) {
            builder.setPageToken(pageToken);
        }
        return withTraceparent(baseStub, traceparent).querySagas(builder.build());
    }

    /**
     * 业务作用：读取单个 Saga 的全局审计事实，分页边界完全交给 Rust Orchestrator。
     *
     * @param tenantId    租户身份
     * @param sagaId      Saga 身份
     * @param pageSize    单页数量，范围 1..1000
     * @param pageToken   上一页返回的不透明 token，可为空
     * @param traceparent 可选的 W3C traceparent
     * @return Rust 返回的审计页
     */
    public GetAuditTrailResponse audit(
            String tenantId,
            String sagaId,
            int pageSize,
            String pageToken,
            String traceparent) {
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        if (pageSize < 1 || pageSize > 1000) {
            throw new SagaProtocolException("page_size must be between 1 and 1000");
        }
        var builder = GetAuditTrailRequest.newBuilder()
                .setKey(SagaKey.newBuilder().setTenantId(tenantId).setSagaId(sagaId).build())
                .setPageSize(pageSize);
        if (pageToken != null) {
            builder.setPageToken(pageToken);
        }
        return withTraceparent(baseStub, traceparent).getAuditTrail(builder.build());
    }

    /**
     * 业务作用：调用 Rust 管理面暂停 Saga；该操作需要独立的管理授权和幂等 operation_id。
     *
     * @param request     Rust 管理请求及并发版本门禁
     * @param traceparent 可选的 W3C traceparent
     * @return Rust 提交后的 Saga 快照
     */
    public SagaSnapshot pause(SagaManagementRequest request, String traceparent) {
        return withTraceparent(adminStub, traceparent).pauseSaga(request);
    }

    /**
     * 业务作用：调用 Rust 管理面恢复暂停的 Saga，继续由 Orchestrator 推进全局状态机。
     *
     * @param request     Rust 管理请求及并发版本门禁
     * @param traceparent 可选的 W3C traceparent
     * @return Rust 提交后的 Saga 快照
     */
    public SagaSnapshot resume(SagaManagementRequest request, String traceparent) {
        return withTraceparent(adminStub, traceparent).resumeSaga(request);
    }

    /**
     * 业务作用：请求 Rust 重试补偿，保持补偿顺序和 fencing 仍由 Orchestrator 裁决。
     *
     * @param request     Rust 管理请求及并发版本门禁
     * @param traceparent 可选的 W3C traceparent
     * @return Rust 提交后的 Saga 快照
     */
    public SagaSnapshot retryCompensation(SagaManagementRequest request, String traceparent) {
        return withTraceparent(adminStub, traceparent).retryCompensation(request);
    }

    /**
     * 业务作用：请求 Rust 重试 resolve，继续裁决外部效果未知的步骤事实。
     *
     * @param request     Rust 管理请求及并发版本门禁
     * @param traceparent 可选的 W3C traceparent
     * @return Rust 提交后的 Saga 快照
     */
    public SagaSnapshot retryResolution(SagaManagementRequest request, String traceparent) {
        return withTraceparent(adminStub, traceparent).retryResolution(request);
    }

    /**
     * 业务作用：请求 Rust 人工关闭 Saga，保留管理审计而不在 Java 侧伪造终态。
     *
     * @param request     Rust 管理请求及并发版本门禁
     * @param traceparent 可选的 W3C traceparent
     * @return Rust 提交后的 Saga 快照
     */
    public SagaSnapshot manualClose(SagaManagementRequest request, String traceparent) {
        return withTraceparent(adminStub, traceparent).manualClose(request);
    }

    /**
     * 业务作用：向 Rust Registry 登记 Java participant 的 capability lease，使路由只选择可达且版本一致的实例。
     *
     * @param capability     participant 的定义、步骤、owner 和 descriptor 摘要
     * @param registrationId 重试中保持不变的登记意图身份
     * @param traceparent    可选的 W3C traceparent
     * @return Rust 接受期限和 route generation
     */
    public CapabilityReceipt registerCapability(
            CapabilityDescriptor capability,
            String registrationId,
            String traceparent) {
        return withTraceparent(registryStub, traceparent).registerCapability(
                RegisterCapabilityRequest.newBuilder()
                        .setCapability(capability)
                        .setRegistrationId(registrationId)
                        .build());
    }

    /**
     * 业务作用：向 Rust Registry 提交已签名 definition artifact，definition 内容由 Registry 校验并冻结摘要。
     *
     * @param artifact    已签名的 definition artifact
     * @param traceparent 可选的 W3C traceparent
     * @return committed/duplicate disposition 与 definition record
     */
    public PublishDefinitionResponse publishDefinition(
            DefinitionArtifact artifact,
            String traceparent) {
        return withTraceparent(registryStub, traceparent).publishDefinition(
                PublishDefinitionRequest.newBuilder().setArtifact(artifact).build());
    }

    /**
     * 业务作用：激活 Rust Registry 中已发布的 definition，决定新 Saga 可使用的版本。
     *
     * @param request     definition key、摘要门禁和 operation_id
     * @param traceparent 可选的 W3C traceparent
     * @return 更新后的 definition record
     */
    public DefinitionRecord activateDefinition(DefinitionOperationRequest request, String traceparent) {
        return withTraceparent(registryStub, traceparent).activateDefinition(request);
    }

    /**
     * 业务作用：将 definition 标记为 deprecated，阻止新路由选择但保留既有实例按原摘要运行。
     *
     * @param request     definition key、摘要门禁和 operation_id
     * @param traceparent 可选的 W3C traceparent
     * @return 更新后的 definition record
     */
    public DefinitionRecord deprecateDefinition(DefinitionOperationRequest request, String traceparent) {
        return withTraceparent(registryStub, traceparent).deprecateDefinition(request);
    }

    /**
     * 业务作用：将 definition 退役，结束 Registry 对该版本的后续使用授权。
     *
     * @param request     definition key、摘要门禁和 operation_id
     * @param traceparent 可选的 W3C traceparent
     * @return 更新后的 definition record
     */
    public DefinitionRecord retireDefinition(DefinitionOperationRequest request, String traceparent) {
        return withTraceparent(registryStub, traceparent).retireDefinition(request);
    }

    /**
     * 业务作用：读取 Rust Registry 的 definition 生命周期和摘要事实，供 participant 启动校验。
     *
     * @param key         tenant、workflow 和 definition version
     * @param traceparent 可选的 W3C traceparent
     * @return Rust 持久化的 definition record
     */
    public DefinitionRecord getDefinition(DefinitionKey key, String traceparent) {
        return withTraceparent(registryStub, traceparent).getDefinition(
                GetDefinitionRequest.newBuilder().setKey(key).build());
    }

    /**
     * 业务作用：关闭 Java client 持有的 gRPC channel，阻止应用停机后继续发送远端 Saga 请求。
     */
    @Override
    public void close() {
        channel.shutdown();
    }

    /**
     * 业务作用：为每次控制面调用附加有界 deadline，并只传播 Rust 可解析的 traceparent。
     *
     * @param traceparent 可选的 W3C traceparent
     * @return 带 deadline 和可选 trace metadata 的 blocking stub
     */
    private <T extends AbstractStub<T>> T withTraceparent(T stub, String traceparent) {
        T deadlineStub = stub.withDeadlineAfter(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        String validTraceparent = SagaTraceparent.validOrNull(traceparent);
        if (validTraceparent == null) {
            return deadlineStub;
        }
        var metadata = new Metadata();
        metadata.put(TRACEPARENT, validTraceparent);
        return deadlineStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    /**
     * 业务作用：把 Java Instant 编码成 protobuf Timestamp，保留 epoch 前的毫秒边界。
     *
     * @param instant 业务 deadline
     * @return protobuf 时间戳
     */
    private static Timestamp timestamp(Instant instant) {
        long epochMillis = instant.toEpochMilli();
        long seconds = Math.floorDiv(epochMillis, 1_000L);
        int nanos = Math.toIntExact(Math.floorMod(epochMillis, 1_000L) * 1_000_000L);
        return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
    }
}
