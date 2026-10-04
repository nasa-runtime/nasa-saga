package io.github.nasaruntime.saga;

/**
 * 异常作用 - 表示 Saga 跨语言协议字段或 JSON envelope 不符合当前合同。
 */
public final class SagaProtocolException extends RuntimeException {

    /**
     * 业务作用：保留可安全展示的协议拒绝原因，供调用方把确定性输入错误映射为本地拒绝。
     *
     * @param message 脱敏后的协议错误描述
     */
    public SagaProtocolException(String message) {
        super(message);
    }

    /**
     * 业务作用：把编解码底层异常包在协议异常中，保持上层只依赖一个错误边界。
     *
     * @param message 脱敏后的协议错误描述
     * @param cause 底层编解码异常
     */
    public SagaProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
