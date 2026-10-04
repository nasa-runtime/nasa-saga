package io.github.nasaruntime.saga;

/**
 * Java `@Saga` service 的瞬态执行故障，对应 Rust `SagaExecutionError::Retryable`。
 */
public final class SagaExecutionException extends RuntimeException {

    private final String reasonCode;

    /**
     * 业务作用：保存可重试故障原因，使 participant 不提交业务终态且不确认 command。
     *
     * @param reasonCode 稳定故障原因码
     */
    public SagaExecutionException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = SagaIds.requireReasonCode(reasonCode);
    }

    /**
     * 业务作用：读取可用于重试指标和审计分类的稳定故障码。
     *
     * @return 故障原因码
     */
    public String reasonCode() {
        return reasonCode;
    }
}
