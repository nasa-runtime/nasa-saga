package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaResultEnvelope;

/**
 * result Outbox 的协议投递 SPI。
 */
@FunctionalInterface
public interface SagaResultOutboxSender {

    /**
     * 业务作用：投递一个已在本地事务中冻结的 result envelope，并返回远端 transport 收据。
     *
     * @param result      已验证的 result envelope
     * @param traceparent 本地 Outbox 保存的链路上下文，可为空
     * @return Rust transport 收据；只有 Committed 或 Duplicate 才能完成 Outbox
     */
    SagaReceiptKind publish(SagaResultEnvelope result, String traceparent);
}
