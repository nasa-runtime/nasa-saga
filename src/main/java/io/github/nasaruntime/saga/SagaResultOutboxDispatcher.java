package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaResultEnvelope;
import io.github.nasaruntime.saga.rc.SagaResultOutboxDispatchResult;

import io.grpc.StatusRuntimeException;
import io.github.nasaruntime.saga.rc.SagaResultOutbox;
import io.github.nasaruntime.saga.mybatis.SagaResultOutboxState;

import java.time.Duration;
import java.util.Objects;

/**
 * participant result Outbox 的单事件推进器，网络调用不进入领取或业务事务。
 */
public final class SagaResultOutboxDispatcher {
    private final SagaResultOutboxLeaseStore leaseStore;
    private final SagaResultOutboxSender sender;
    private final long requestBudgetMs;
    private final SagaResultOutboxEvidenceVerifier evidenceVerifier;

    /**
     * 业务作用：以默认五秒发送预算绑定 result 推进器，sender 必须在该预算内结束。
     *
     * @param leaseStore 本地 lease 存储
     * @param sender     强制限制在五秒内结束的发送器
     *                   返回：仅在剩余 lease 大于发送预算时投递的推进器。
     */
    public SagaResultOutboxDispatcher(SagaResultOutboxLeaseStore leaseStore, SagaResultOutboxSender sender) {
        this(leaseStore, sender, Duration.ofSeconds(5));
    }

    /**
     * 业务作用：把 sender 的实际网络超时纳入领取后的 lease 预算门禁。
     *
     * @param leaseStore    本地 lease 存储
     * @param sender        在 requestBudget 内结束的发送器
     * @param requestBudget sender 强制执行的完整请求超时
     *                      返回：预算不足拒绝发送，失权拒绝回写的推进器。
     */
    public SagaResultOutboxDispatcher(SagaResultOutboxLeaseStore leaseStore, SagaResultOutboxSender sender,
                                      Duration requestBudget) {
        this(leaseStore, sender, requestBudget, outbox -> true);
    }

    /**
     * 业务作用：将逐事件业务证据复验放在领取之后、网络发送之前，混合队列中的不可证明结果独立隔离。
     *
     * @param leaseStore       原事件的 lease 与 fencing 存储
     * @param sender           在 requestBudget 内结束的发送器
     * @param requestBudget    网络请求所需的完整预算
     * @param evidenceVerifier 在独立只读快照中核对事件所需业务事实，不在业务事务内调用网络
     *                         返回：证据成立且剩余租期足够才发送，隔离与送达均受同一领取 token 约束。
     */
    public SagaResultOutboxDispatcher(SagaResultOutboxLeaseStore leaseStore, SagaResultOutboxSender sender,
                                      Duration requestBudget, SagaResultOutboxEvidenceVerifier evidenceVerifier) {
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.evidenceVerifier = Objects.requireNonNull(evidenceVerifier, "evidenceVerifier");
        this.requestBudgetMs = Objects.requireNonNull(requestBudget, "requestBudget").toMillis();
        if (requestBudgetMs <= 0) throw new IllegalArgumentException("request budget must be positive");
    }

    /**
     * 业务作用：凭领取事务的不可变 token 投递，失权后的迟到结果不能覆盖当前状态。
     *
     * @param eventId      稳定 result event 身份
     * @param owner        worker 名称，不作为本次动作的唯一权威
     * @param nowMs        调度时刻
     * @param leaseUntilMs 本次 lease 到期时刻
     * @param retryAtMs    待重试时刻
     * @return 未领取或存储层已隔离损坏记录时为 null；否则返回裁决；预算耗尽或回写失权时抛出持久化异常。
     */
    public SagaResultOutboxDispatchResult dispatch(String eventId, String owner, long nowMs,
                                                   long leaseUntilMs, long retryAtMs) {
        // 持久化主键本身也可能损坏；参数化领取允许它进入隔离路径，网络身份仍须通过记录构造器验证。
        Objects.requireNonNull(eventId, "event_id");
        SagaIds.requireOpaque(owner, "lease_owner", 256);
        if (nowMs < 0 || leaseUntilMs <= nowMs || retryAtMs < nowMs) {
            throw new IllegalArgumentException("dispatcher timing values are invalid");
        }
        SagaDispatchLease lease = new SagaDispatchLease(nowMs, leaseUntilMs);
        SagaResultOutbox outbox = leaseStore.claim(eventId, owner, nowMs, leaseUntilMs);
        if (outbox == null) return null;
        // owner 可以跨进程复用，只有本次事务冻结的 token 才能授权回写。
        if (!eventId.equals(outbox.eventId()) || !owner.equals(outbox.leaseOwner())
                || !Objects.equals(outbox.leaseUntilMs(), leaseUntilMs) || outbox.fencingToken() <= 0
                || outbox.state() != SagaResultOutboxState.IN_FLIGHT) {
            throw new SagaPersistenceException("invalid result claim snapshot", null);
        }
        lease.requireRemaining(requestBudgetMs);
        SagaResultOutboxState state;
        Integer status = null;
        String error = null;
        try {
            if (!evidenceVerifier.permits(outbox)) {
                // 只隔离当前事件，不能借其它事件的完整证据释放不可证明的成功或无效果承诺。
                state = SagaResultOutboxState.NEEDS_ATTENTION;
                error = "local_result_evidence_unavailable";
            } else {
                // 证据查询会消耗租期；必须在查询结束后再次为整个网络请求保留预算。
                lease.requireRemaining(requestBudgetMs);
                SagaReceiptKind receipt = sender.publish(SagaResultEnvelope.decode(outbox.payload()), outbox.traceparent());
                state = switch (receipt) {
                    case COMMITTED, DUPLICATE -> SagaResultOutboxState.DELIVERED;
                    case DETERMINISTIC_REJECT -> SagaResultOutboxState.NEEDS_ATTENTION;
                    default -> SagaResultOutboxState.PENDING;
                };
                if (state == SagaResultOutboxState.NEEDS_ATTENTION) {
                    status = 422;
                    error = "deterministic_result_reject";
                } else if (state == SagaResultOutboxState.PENDING) error = "retryable";
            }
        } catch (SagaHttpException | StatusRuntimeException exception) {
            SagaRemoteFailure failure = SagaRemoteFailure.classify(exception);
            // 确定性拒绝需要人工处置；保留原事件与业务事实，不能换身份绕过授权或合同门禁。
            state = failure.deterministic() ? SagaResultOutboxState.NEEDS_ATTENTION : SagaResultOutboxState.PENDING;
            status = exception instanceof SagaHttpException http && http.statusCode() >= 0 ? http.statusCode() : null;
            error = failure.errorCode();
        } catch (SagaProtocolException exception) {
            state = SagaResultOutboxState.NEEDS_ATTENTION;
            error = "local_result_contract_invalid";
        } catch (RuntimeException exception) {
            state = SagaResultOutboxState.PENDING;
            error = "uncertain";
        }
        // 发送可产生远端效果，但到期后的本地执行流没有权限确认该效果。
        lease.requireRemaining(0);
        if (!leaseStore.settle(eventId, owner, outbox.fencingToken(), state,
                state == SagaResultOutboxState.PENDING ? retryAtMs : null, error)) {
            throw new SagaPersistenceException("result Outbox lease was lost before settlement", null);
        }
        return new SagaResultOutboxDispatchResult(eventId, state, status, error);
    }
}
