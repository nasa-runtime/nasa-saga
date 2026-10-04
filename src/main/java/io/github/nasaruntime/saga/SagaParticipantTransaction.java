package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaCommandEnvelope;
import io.github.nasaruntime.saga.rc.SagaDeliveryReceipt;
import io.github.nasaruntime.saga.rc.SagaStepDescriptor;

import java.util.concurrent.CompletionStage;

/**
 * Java participant 的本地事务边界，对应 Rust `ParticipantRuntime` 的事务 wrapper。
 *
 * <p>实现必须使用 MyBatis 把 Inbox、participant gate、业务事实和 result Outbox 放进同一数据库事务；
 * 网络调用只能由提交后的 result dispatcher 执行。该接口不允许调用方在业务回调中自行确认 transport receipt。</p>
 */
@FunctionalInterface
public interface SagaParticipantTransaction {

    /**
     * 业务作用：执行一个 command 的完整本地处理序列，并在本地事实提交后返回 transport 收据。
     *
     * <p>实现的时序必须是 Inbox claim → gate admission → service invocation → result Outbox →
     * COMMIT → receipt。local-fenceable 的 cancel 不调用 invocation，而是在 gate 内完成屏障裁决；
     * externally-cancellable 的 cancel 必须把上下文目标绑定到 execute；resolve 必须把目标绑定到 gate
     * 已判定的 execute 或 compensate。</p>
     *
     * @param command     已通过 transport 身份和 descriptor 合同校验的 command
     * @param descriptor  本地 `@Saga` descriptor
     * @param producer    已认证的 Rust Orchestrator logical identity
     * @param traceparent 入站链路上下文，可为空
     * @param invocation  业务阶段回调
     * @return 本地提交完成后的 Committed、Duplicate 或确定性拒绝收据
     */
    CompletionStage<SagaDeliveryReceipt> handle(
            SagaCommandEnvelope command,
            SagaStepDescriptor descriptor,
            String producer,
            String traceparent,
            SagaStepInvocation invocation);
}
