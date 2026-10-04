package io.github.nasaruntime.saga;

/**
 * Rust Saga gRPC request/response transport 的封闭收据类型。
 */
public enum SagaReceiptKind {
    /**
     * 未知或未设置的收据，不能推动 Outbox。
     */
    UNSPECIFIED(0),
    /**
     * 接收方本地事务已提交。
     */
    COMMITTED(1),
    /**
     * 接收方 Inbox 已吸收重复投递。
     */
    DUPLICATE(2),
    /**
     * 身份或协议合同确定性拒绝。
     */
    DETERMINISTIC_REJECT(3),
    /**
     * 本地事务未得到可提交结论，需要保留原身份重投。
     */
    RETRYABLE(4);

    private final int wireValue;

    /**
     * 业务作用：将投递裁决绑定到稳定 wire 值，避免依赖枚举排序。
     *
     * @param wireValue 跨语言协议中的固定收据值
     */
    SagaReceiptKind(int wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * 业务作用：读取与 Rust protobuf enum 对齐的数值。
     *
     * @return wire enum 数值
     */
    public int wireValue() {
        return wireValue;
    }

    /**
     * 业务作用：把 protobuf enum 数值收窄为 Java 封闭收据类型，未知值按未决处理。
     *
     * @param wireValue protobuf enum 数值
     * @return 对应收据类型，未知值返回 UNSPECIFIED
     */
    public static SagaReceiptKind fromWireValue(int wireValue) {
        for (SagaReceiptKind value : values()) {
            if (value.wireValue == wireValue) {
                return value;
            }
        }
        return UNSPECIFIED;
    }
}
