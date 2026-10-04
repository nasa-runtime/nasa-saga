package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;
import io.github.nasaruntime.saga.SagaReceiptKind;

/**
 * Saga gRPC command/result 投递收据。
 *
 * @param kind   封闭收据类型
 * @param reason 确定性拒绝原因码，可为空
 */
public record SagaDeliveryReceipt(SagaReceiptKind kind, String reason) {

    /**
     * 业务作用：在本地构造 transport 收据时保持 Rust 的封闭枚举和原因码边界。
     *
     * @param kind   收据类型
     * @param reason 低基数原因码，可为空
     */
    public SagaDeliveryReceipt {
        if (kind == null) {
            throw new IllegalArgumentException("receipt kind is required");
        }
        if (reason != null && !reason.isEmpty()) {
            SagaIds.requireReasonCode(reason);
        }
        if (kind == SagaReceiptKind.DETERMINISTIC_REJECT && (reason == null || reason.isEmpty())) {
            throw new SagaProtocolException("deterministic rejection requires a reason code");
        }
        if (kind != SagaReceiptKind.DETERMINISTIC_REJECT && reason != null && !reason.isEmpty()) {
            throw new SagaProtocolException("receipt reason is only valid for deterministic rejection");
        }
    }

    /**
     * 业务作用：把 Rust generated protobuf 收据转换为 Java 不依赖生成类的领域收据。
     *
     * @param receipt Rust protobuf 收据
     * @return Java 收据
     */
    public static SagaDeliveryReceipt fromProto(
            io.github.nasaruntime.saga.proto.transport.v1.SagaDeliveryReceipt receipt) {
        return new SagaDeliveryReceipt(
                SagaReceiptKind.fromWireValue(receipt.getKindValue()),
                receipt.getReason());
    }

    /**
     * 业务作用：把 Java 收据编码为 Rust generated protobuf 收据。
     *
     * @return Rust protobuf 收据
     */
    public io.github.nasaruntime.saga.proto.transport.v1.SagaDeliveryReceipt toProto() {
        return io.github.nasaruntime.saga.proto.transport.v1.SagaDeliveryReceipt.newBuilder()
                .setKind(io.github.nasaruntime.saga.proto.transport.v1.SagaReceiptKind.forNumber(kind.wireValue()))
                .setReason(reason == null ? "" : reason)
                .build();
    }

    /**
     * 业务作用：判断投递端是否可以把原始 Outbox 事实标记为已送达。
     *
     * @return COMMITTED 或 DUPLICATE 返回 true，其余收据返回 false
     */
    public boolean advancesOutbox() {
        return kind == SagaReceiptKind.COMMITTED || kind == SagaReceiptKind.DUPLICATE;
    }

    /**
     * 业务作用：判断本次结果是否仍可能对应远端已提交效果，调用方必须保留原身份重投。
     *
     * @return RETRYABLE 或 UNSPECIFIED 返回 true
     */
    public boolean isUnresolved() {
        return kind == SagaReceiptKind.RETRYABLE || kind == SagaReceiptKind.UNSPECIFIED;
    }
}
