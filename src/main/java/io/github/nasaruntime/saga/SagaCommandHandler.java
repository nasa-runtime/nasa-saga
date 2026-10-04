package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaCommandEnvelope;
import io.github.nasaruntime.saga.rc.SagaDeliveryReceipt;

import java.util.concurrent.CompletionStage;

/**
 * Java participant 的本地 command 事务适配边界。
 *
 * <p>实现必须在一个本地事务中完成 Inbox、participant gate、业务事实和 result Outbox，
 * 只有这些事实一起提交后才能返回 {@link SagaReceiptKind#COMMITTED} 或
 * {@link SagaReceiptKind#DUPLICATE}。
 */
@FunctionalInterface
public interface SagaCommandHandler {

    /**
     * 业务作用：接收已经完成 mTLS 身份和 envelope 合同校验的 command，并提交 participant 本地事实。
     *
     * @param command 已通过身份派生校验的 command envelope
     * @param producer 由 mTLS certificate 映射得到的逻辑 Orchestrator 身份
     * @param traceparent 入站的 W3C traceparent，缺失时为 {@code null}
     * @return 本地事务完成后的收据；未决或瞬态故障应返回 {@code RETRYABLE}
     */
    CompletionStage<SagaDeliveryReceipt> handle(
            SagaCommandEnvelope command,
            String producer,
            String traceparent);
}
