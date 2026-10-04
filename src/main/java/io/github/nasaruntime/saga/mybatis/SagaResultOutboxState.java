package io.github.nasaruntime.saga.mybatis;

/**
 * Java participant result Outbox 的持久状态。
 */
public enum SagaResultOutboxState {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    NEEDS_ATTENTION
}
