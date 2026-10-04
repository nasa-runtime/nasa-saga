package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaCommandEnvelope;
import io.github.nasaruntime.saga.rc.SagaContext;
import io.github.nasaruntime.saga.rc.SagaDeliveryReceipt;
import io.github.nasaruntime.saga.rc.SagaPayload;
import io.github.nasaruntime.saga.rc.SagaStepDescriptor;
import io.github.nasaruntime.saga.rc.SagaStepResult;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 把 `@Saga` service 接到现有 command ingress 的适配器，对应 Rust 宏生成的 command adapter。
 *
 * <p>该类只负责 descriptor 复验和 phase 分发；Inbox、gate、MyBatis 本地事务、result Outbox
 * 以及 ACK 边界由 {@link SagaParticipantTransaction} 持有。</p>
 */
public final class SagaAnnotatedCommandHandler implements SagaCommandHandler {

    private final SagaStep service;
    private final SagaStepDescriptor descriptor;
    private final SagaParticipantTransaction transaction;

    /**
     * 业务作用：把一个带 `@Saga` 合同的 service 绑定到 participant 本地事务 wrapper。
     *
     * @param service     `@Saga` service 实例
     * @param transaction 使用 MyBatis 实现的本地事务 wrapper
     */
    public SagaAnnotatedCommandHandler(SagaStep service, SagaParticipantTransaction transaction) {
        this.service = Objects.requireNonNull(service, "service");
        this.descriptor = SagaStepDescriptor.from(service.getClass());
        this.transaction = Objects.requireNonNull(transaction, "transaction");
    }

    /**
     * 业务作用：读取该 handler 的静态步骤合同，供 capability registration 与启动预检使用。
     *
     * @return 已校验 descriptor
     */
    public SagaStepDescriptor descriptor() {
        return descriptor;
    }

    /**
     * 业务作用：在进入本地事务前完成 Rust route、payload、phase 和补偿/解决策略复验。
     *
     * @param command     Rust Orchestrator command
     * @param producer    已认证的 Rust Orchestrator identity
     * @param traceparent 入站链路上下文，可为空
     * @return 本地事务完成后的 transport receipt
     */
    @Override
    public CompletionStage<SagaDeliveryReceipt> handle(
            SagaCommandEnvelope command,
            String producer,
            String traceparent) {
        descriptor.verifyCommand(command);
        SagaIds.requireStructured(producer, "producer");
        SagaPayload payload = command.businessPayload();
        return transaction.handle(
                command,
                descriptor,
                producer,
                traceparent,
                (context, ignoredPayload) -> invoke(command.phase(), context, payload));
    }

    /**
     * 业务作用：按 Rust 宏生成 adapter 的 phase 分发调用业务 service，并把领域结果转为统一 result 状态。
     *
     * @param phase   command 阶段
     * @param context participant transaction 构造的上下文
     * @param payload 已校验的原始正文
     * @return 可进入本地 result Outbox 的阶段结论
     */
    private CompletionStage<SagaStepResult> invoke(
            String phase,
            SagaContext context,
            SagaPayload payload) {
        return switch (phase) {
            case "execute" -> service.execute(context, payload)
                    .thenApply(SagaStepResult::from)
                    .thenApply(result -> descriptor.normalizeResult(phase, result));
            case "compensate" -> service.compensate(context, payload).thenApply(SagaStepResult::from);
            case "cancel" -> {
                if (!"externally-cancellable".equals(descriptor.cancelMode())) {
                    yield CompletableFuture.failedStage(
                            new SagaProtocolException("local cancel is owned by the participant gate"));
                }
                yield service.cancel(context.withTargetPhase("execute"), payload)
                        .thenApply(SagaStepResult::from);
            }
            case "resolve" -> service.resolve(context, payload).thenApply(SagaStepResult::from);
            default -> CompletableFuture.failedStage(
                    new SagaProtocolException("Saga command phase is not supported"));
        };
    }
}
