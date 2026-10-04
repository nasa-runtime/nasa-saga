package io.github.nasaruntime.saga;

/**
 * Rust `natelemetry` 使用的 W3C traceparent v00 校验器。
 */
public final class SagaTraceparent {

    /**
     * 业务作用：限制链路上下文规范化为无状态入口，不持有请求或凭据。
     * 参数说明：无。
     */
    private SagaTraceparent() {}

    /**
     * 业务作用：只传播 Rust 能解析且不含全零 trace/span 身份的 traceparent，避免错误 header 建立错误因果链。
     *
     * @param value 入站或待发送的 traceparent
     * @return 合法原值；缺失、重复处理后的空值或非法值返回 {@code null}
     */
    public static String validOrNull(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("-", -1);
        if (parts.length != 4 || !"00".equals(parts[0])
                || parts[1].length() != 32 || parts[2].length() != 16 || parts[3].length() != 2
                || !lowerHex(parts[1]) || !lowerHex(parts[2]) || !lowerHex(parts[3])
                || allZero(parts[1]) || allZero(parts[2])) {
            return null;
        }
        return value;
    }

    /**
     * 业务作用：确认 traceparent 字段使用 Rust 要求的小写十六进制编码。
     *
     * @param value traceparent 的一个字段
     * @return 全部字符合法时为 {@code true}
     */
    private static boolean lowerHex(String value) {
        return value.chars().allMatch(character -> character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f');
    }

    /**
     * 业务作用：拒绝 W3C 禁止的全零 trace 或 span identity。
     *
     * @param value trace 或 span 字段
     * @return 全零时为 {@code true}
     */
    private static boolean allZero(String value) {
        return value.chars().allMatch(character -> character == '0');
    }
}
