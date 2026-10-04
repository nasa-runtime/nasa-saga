package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.CancelOutcome;
import io.github.nasaruntime.saga.rc.CompensationOutcome;
import io.github.nasaruntime.saga.rc.SagaContext;
import io.github.nasaruntime.saga.rc.SagaOutcome;
import io.github.nasaruntime.saga.rc.SagaPayload;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

/**
 * `@Saga` participant 的业务 service 接口，对应 Rust `SagaStep` 以及可选的
 * `SagaCancelStep`、`SagaResolveStep`。
 *
 * <p>业务实现只返回领域结果；本地事务、Inbox、gate 和 result Outbox 由 participant transaction
 * adapter 统一包裹。CompletionStage 异常只表示本次没有可提交结论，应保持原 command 身份重投。</p>
 */
public interface SagaStep {

    /**
     * 业务作用：执行正向业务效果并返回可提交的 Rust 兼容结论。
     *
     * @param context 框架构造的只读 Saga 上下文
     * @param payload 已按 @Saga 合同校验的业务正文，可为空
     * @return 成功、拒绝、未知或冻结结果；异常表示本次应回滚重试
     */
    CompletionStage<SagaOutcome> execute(SagaContext context, SagaPayload payload);

    /**
     * 业务作用：按稳定 effect_id 幂等撤销本步骤已经产生的正向效果。
     *
     * @param context 框架构造的补偿上下文
     * @param payload 补偿输入，通常由本地事实恢复，可为空
     * @return 成功、未知或冻结结果；不存在拒绝分支
     */
    CompletionStage<CompensationOutcome> compensate(SagaContext context, SagaPayload payload);

    /**
     * 业务作用：为 externally-cancellable 步骤提供真实取消屏障裁决。
     *
     * @param context 框架构造的取消上下文
     * @param payload 取消输入，可为空
     * @return 取消确认、已有终态或仍需解决的裁决
     */
    default CompletionStage<CancelOutcome> cancel(SagaContext context, SagaPayload payload) {
        return CompletableFuture.failedStage(
                new SagaExecutionException("cancel_handler_not_configured"));
    }

    /**
     * 业务作用：为 resolve-only 或 externally-cancellable 步骤查询外部效果并收敛未知结果。
     *
     * @param context 框架构造的解决上下文
     * @param payload 解决输入，可为空
     * @return 正向结果；仍未知时返回 UNKNOWN
     */
    default CompletionStage<SagaOutcome> resolve(SagaContext context, SagaPayload payload) {
        return CompletableFuture.failedStage(
                new SagaExecutionException("resolve_handler_not_configured"));
    }
}
