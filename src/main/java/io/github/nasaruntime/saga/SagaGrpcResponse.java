package io.github.nasaruntime.saga;

import com.google.protobuf.Timestamp;
import io.grpc.Status;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 将远端响应损坏与本地请求合同错误区分，避免未知提交结果被当作确定拒绝。
 */
final class SagaGrpcResponse {

    /**
     * 业务作用：禁止构造无状态响应边界。
     * 参数说明：无。
     * 返回：仅类内部使用。
     */
    private SagaGrpcResponse() {}

    /**
     * 业务作用：只在网络返回后的解码阶段标记证据丢失，保留原业务身份等待权威收据。
     *
     * @param decoder 对已经收到的 protobuf 响应进行领域映射
     * @param <T>     领域响应类型
     * @return 完整的领域响应；合同损坏以 DATA_LOSS 表示不确定结果。
     */
    static <T> T decode(Supplier<T> decoder) {
        try {
            return decoder.get();
        } catch (SagaProtocolException | IllegalArgumentException failure) {
            // 远端可能已完成提交，响应损坏不能成为隔离原始业务意图的依据。
            throw Status.DATA_LOSS.withDescription("Saga response contract is invalid")
                    .withCause(failure).asRuntimeException();
        }
    }

    /**
     * 业务作用：按 protobuf Timestamp 合同解释远端时间，阻止 Java 更宽的日期范围或纳秒规范化改变权威事实。
     *
     * @param value 已存在的 protobuf 时间戳
     * @return wire 范围内的原始时刻；缺失或越界抛出协议异常，由调用方的响应边界转换为 DATA_LOSS。
     */
    static Instant instant(Timestamp value) {
        // 必须先验证原始秒和纳秒，非法时间不能通过 Instant 转换获得快照或租约权威。
        if (value == null || value.getSeconds() < -62_135_596_800L || value.getSeconds() > 253_402_300_799L
                || value.getNanos() < 0 || value.getNanos() > 999_999_999) {
            throw new SagaProtocolException("Rust gRPC timestamp is invalid");
        }
        try {
            return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
        } catch (DateTimeException | ArithmeticException exception) {
            throw new SagaProtocolException("Rust gRPC timestamp is invalid", exception);
        }
    }

    /**
     * 业务作用：将已验证的远端时刻投影为 Java 使用的 epoch 毫秒，拒绝无法表示的截止时间。
     *
     * @param value 已存在的 protobuf 时间戳
     * @return 合法时刻的毫秒投影；wire 非法或毫秒溢出时抛出协议异常。
     */
    static long timestampMillis(Timestamp value) {
        try {
            return instant(value).toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new SagaProtocolException("Rust gRPC timestamp cannot be represented in milliseconds", exception);
        }
    }
}
