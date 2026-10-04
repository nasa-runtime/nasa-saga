package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaDispatchResult;
import io.github.nasaruntime.saga.rc.SagaHttpStartReceipt;
import io.github.nasaruntime.saga.rc.SagaSnapshotView;
import io.github.nasaruntime.saga.rc.SagaStartIdentity;

import com.fasterxml.jackson.databind.JsonNode;
import io.grpc.StatusRuntimeException;
import io.github.nasaruntime.saga.mybatis.SagaIntentState;
import io.github.nasaruntime.saga.rc.SagaStartIntent;

import java.util.Objects;

/**
 * reliable start 的单次 dispatcher 推进器。
 */
public final class SagaStartIntentDispatcher {

    private final SagaStartSender controlPlane;
    private final SagaStartIntentLeaseStore leaseStore;

    /**
     * 业务作用：绑定 Rust control plane 与本地短事务 lease/fencing 存储。
     *
     * @param controlPlane Rust HTTP control plane
     * @param leaseStore   本地 lease/fencing 实现
     *                     返回：网络调用与数据库事务分离的推进器。
     */
    public SagaStartIntentDispatcher(HttpSagaControlPlane controlPlane, SagaStartIntentLeaseStore leaseStore) {
        this((SagaStartSender) controlPlane, leaseStore);
    }

    /**
     * 业务作用：绑定 HTTP 或 gRPC 的冻结请求发送入口，保持网络与本地 claim/settle 短事务分离。
     *
     * @param controlPlane 带强制调用预算的远端 start sender
     * @param leaseStore   本地 lease/fencing 实现
     *                     返回：只在有效 token 与 lease 内推进已提交意图的 dispatcher。
     */
    public SagaStartIntentDispatcher(SagaStartSender controlPlane, SagaStartIntentLeaseStore leaseStore) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
    }

    /**
     * 业务作用：领取不可变意图快照，校验完整性后发送，仅凭本次 token 和未耗尽的 lease 回写。
     *
     * @param intentId     数据库中的原始 intent 主键；损坏身份也必须先经过持锁领取与隔离
     * @param owner        当前 worker
     * @param nowMs        调度时刻
     * @param leaseUntilMs 本次 lease 到期时刻，必须覆盖 transport 超时与本地提交窗口
     * @param retryAtMs    瞬态失败的下一次投递时间
     * @return 本轮结果；未领取或已隔离为 null；失权时抛出持久化异常并保留待恢复状态。
     */
    public SagaDispatchResult dispatch(String intentId, String owner, long nowMs, long leaseUntilMs, long retryAtMs) {
        // 恢复不能在主键格式检查处中断；参数化领取定位原行，记录构造器仍拒绝非法 UUID 的发送快照。
        Objects.requireNonNull(intentId, "intent_id");
        SagaIds.requireOpaque(owner, "lease_owner", 256);
        if (nowMs < 0 || leaseUntilMs <= nowMs || retryAtMs < nowMs) {
            throw new IllegalArgumentException("dispatcher timing values are invalid");
        }
        SagaDispatchLease lease = new SagaDispatchLease(nowMs, leaseUntilMs);
        SagaStartIntent intent = leaseStore.claim(intentId, owner, nowMs, leaseUntilMs);
        if (intent == null) return null;
        // owner 不是领取身份；只使用持锁事务返回的 token，禁止另查最新状态作为执行权威。
        if (!intentId.equals(intent.intentId()) || !owner.equals(intent.leaseOwner())
                || !Objects.equals(intent.leaseUntilMs(), leaseUntilMs) || intent.fencingToken() <= 0
                || intent.state() != SagaIntentState.IN_FLIGHT) {
            throw new SagaPersistenceException("invalid start claim snapshot", null);
        }
        lease.requireRemaining(0);
        SagaIntentState state;
        Integer status = null;
        String error = integrityError(intent);
        if (error != null) {
            // 本地正文或身份已偏离冻结事实时不能重新签名；原摘要保留用于人工核对。
            state = SagaIntentState.NEEDS_ATTENTION;
        } else {
            lease.requireRemaining(controlPlane.requestTimeout().toMillis());
            try {
                SagaStartResult receipt = controlPlane.startRaw(intent.requestBody(), intent.traceparent());
                state = matchesIntent(intent, receipt.saga())
                        ? receipt.disposition() == SagaStartDisposition.COMMITTED
                          ? SagaIntentState.COMMITTED : SagaIntentState.DUPLICATE : SagaIntentState.NEEDS_ATTENTION;
                status = receipt instanceof SagaHttpStartReceipt ? 200 : null;
                error = state == SagaIntentState.NEEDS_ATTENTION ? "returned_snapshot_mismatch" : null;
            } catch (SagaHttpException | StatusRuntimeException exception) {
                SagaRemoteFailure failure = SagaRemoteFailure.classify(exception);
                // 与 direct 和 result 共用拒绝语义；不确定结果继续使用冻结身份等待恢复。
                state = failure.deterministic() ? SagaIntentState.NEEDS_ATTENTION : SagaIntentState.PENDING;
                status = exception instanceof SagaHttpException http && http.statusCode() >= 0 ? http.statusCode() : null;
                error = failure.errorCode();
            } catch (RuntimeException exception) {
                state = SagaIntentState.PENDING;
                error = "uncertain";
            }
        }
        // 迟到网络结果不能解除失权；回写失败直接传播，不能再进入另一条回写路径。
        lease.requireRemaining(0);
        if (!leaseStore.settle(intentId, owner, intent.fencingToken(), state, status, error,
                state == SagaIntentState.PENDING ? retryAtMs : null, System.currentTimeMillis())) {
            throw new SagaPersistenceException("Saga start intent lease was lost before settlement", null);
        }
        return new SagaDispatchResult(intentId, state, status, error);
    }

    /**
     * 业务作用：复验冻结 raw bytes 及请求身份，阻止持久化偏差成为可信远端发起意图。
     *
     * @param intent 本次领取的不可变快照
     * @return 低敏完整性错误码；正文与冻结列一致时为空。
     */
    private static String integrityError(SagaStartIntent intent) {
        byte[] body = intent.requestBody();
        if (!SagaHttpStartCodec.sha256Hex(body).equals(intent.requestSha256())) {
            return "local_request_digest_mismatch";
        }
        try {
            // 两种发送协议共用完整请求合同；损坏正文不能在 gRPC 转换失败后被当成网络不确定而永久重投。
            SagaHttpStartCodec.decode(body, intent.traceparent());
            JsonNode value = SagaEnvelopeCodec.decodeJsonStrict(body);
            boolean matches = value.isObject()
                    && textMatches(value, "tenant_id", intent.tenantId())
                    && textMatches(value, "saga_id", intent.sagaId())
                    && textMatches(value, "workflow", intent.workflow())
                    && textMatches(value, "business_key", intent.businessKey())
                    && textMatches(value, "trigger_id", intent.triggerId())
                    && textMatches(value, "expected_definition_digest", intent.expectedDefinitionDigest())
                    && numberMatches(value, "definition_version", (long) intent.definitionVersion())
                    && numberMatches(value, "deadline_at_ms", intent.deadlineAtMs());
            return matches ? null : "local_request_identity_mismatch";
        } catch (SagaProtocolException exception) {
            return "local_request_contract_invalid";
        }
    }

    /**
     * 业务作用：以严格字符串类型核对必填或可空身份，拒绝 JSON 标量隐式转换。
     *
     * @param value    请求对象
     * @param field    合同字段
     * @param expected 冻结列，可为空
     * @return 字段类型与原值均一致时为 true。
     */
    private static boolean textMatches(JsonNode value, String field, String expected) {
        JsonNode node = value.path(field);
        return expected == null ? node.isMissingNode() || node.isNull()
                : node.isTextual() && expected.equals(node.textValue());
    }

    /**
     * 业务作用：精确比对定义版本与业务期限，拒绝截断数值或字符串转换。
     *
     * @param value    请求对象
     * @param field    合同字段
     * @param expected 冻结数值，可为空
     * @return 整数类型和完整值一致时为 true。
     */
    private static boolean numberMatches(JsonNode value, String field, Long expected) {
        JsonNode node = value.path(field);
        return expected == null ? node.isMissingNode() || node.isNull()
                : node.isIntegralNumber() && node.canConvertToLong() && node.longValue() == expected;
    }

    /**
     * 业务作用：使用与 direct 相同的身份规则，确认收据指向冻结的业务槽位与定义。
     *
     * @param intent   本地冻结身份
     * @param snapshot 远端快照
     * @return 身份与定义前置条件一致时为 true。
     */
    private static boolean matchesIntent(SagaStartIntent intent, SagaSnapshotView snapshot) {
        return new SagaStartIdentity(intent.tenantId(), intent.sagaId(), intent.workflow(),
                intent.definitionVersion(), intent.expectedDefinitionDigest(), intent.businessKey()).matches(snapshot);
    }
}
