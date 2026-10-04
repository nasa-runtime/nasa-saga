package io.github.nasaruntime.saga;

/**
 * HTTP 调用离开网络边界后的业务分类。
 */
public enum SagaHttpDisposition {
    /**
     * 请求已被 Rust 按输入、权限或状态确定拒绝。
     */
    DETERMINISTIC_REJECT,
    /**
     * Rust 明确返回暂时不可用，原业务身份应保留并退避重试。
     */
    RETRYABLE,
    /**
     * Java 无法确认 Rust 是否已经提交，必须用原始身份查询或重试。
     */
    UNCERTAIN
}
