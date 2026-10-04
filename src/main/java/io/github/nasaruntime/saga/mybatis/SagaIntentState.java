package io.github.nasaruntime.saga.mybatis;

/**
 * reliable start intent 的持久状态。
 */
public enum SagaIntentState {
    PENDING,
    IN_FLIGHT,
    COMMITTED,
    DUPLICATE,
    NEEDS_ATTENTION
}
