package io.github.nasaruntime.saga;

/**
 * Rust Orchestrator 对 Saga start 的持久裁决。
 */
public enum SagaStartDisposition {
    /**
     * 首次提交了实例、首条 command Outbox 和 timer。
     */
    COMMITTED,
    /**
     * 相同 Saga 身份和请求摘要已经提交。
     */
    DUPLICATE,
    /**
     * 远端未返回可接受的 start 裁决。
     */
    UNSPECIFIED
}
