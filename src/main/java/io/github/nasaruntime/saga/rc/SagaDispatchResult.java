package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.mybatis.SagaIntentState;

/**
 * reliable start dispatcher 的一次本地状态推进结果。
 *
 * @param intentId   intent 身份
 * @param state      本地推进后的状态
 * @param httpStatus 最近 HTTP 状态，可为空
 * @param errorCode  低基数状态码，可为空
 */
public record SagaDispatchResult(
        String intentId,
        SagaIntentState state,
        Integer httpStatus,
        String errorCode) {
}
