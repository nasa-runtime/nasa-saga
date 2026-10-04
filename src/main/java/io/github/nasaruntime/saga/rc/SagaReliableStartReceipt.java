package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

import io.github.nasaruntime.saga.mybatis.SagaIntentState;

/**
 * 本地 reliable start 的受理收据。
 *
 * @param intentId 本地 intent 身份
 * @param state    本地持久状态；PENDING 不代表 Rust 已创建 Saga
 */
public record SagaReliableStartReceipt(String intentId, SagaIntentState state) {

    /**
     * 业务作用：明确区分本地事务受理和 Rust 远端 start 裁决。
     */
    public SagaReliableStartReceipt {
        SagaIds.requireUuid(intentId, "intent_id");
        if (state != SagaIntentState.PENDING) {
            throw new SagaProtocolException("new reliable start must be pending");
        }
    }
}
