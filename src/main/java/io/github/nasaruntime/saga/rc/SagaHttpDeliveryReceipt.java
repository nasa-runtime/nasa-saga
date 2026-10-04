package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaProtocolException;
import io.github.nasaruntime.saga.SagaReceiptKind;

/**
 * Rust managed HTTP command/result receipt。
 *
 * @param kind transport 收据类型；HTTP 合同不携带 gRPC reason 字段
 */
public record SagaHttpDeliveryReceipt(SagaReceiptKind kind) {

    /**
     * 业务作用：限制 HTTP receipt 只能表达 Rust managed HTTP 的三种公开状态。
     */
    public SagaHttpDeliveryReceipt {
        if (kind != SagaReceiptKind.COMMITTED && kind != SagaReceiptKind.DUPLICATE
                && kind != SagaReceiptKind.DETERMINISTIC_REJECT) {
            throw new SagaProtocolException("unsupported Saga HTTP delivery receipt");
        }
    }

    /**
     * 业务作用：判断 result Outbox 是否可以前移到已投递状态。
     *
     * @return Rust 返回 Committed 或 Duplicate 时为 {@code true}
     */
    public boolean advancesOutbox() {
        return kind == SagaReceiptKind.COMMITTED || kind == SagaReceiptKind.DUPLICATE;
    }
}
