package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.mybatis.SagaResultOutboxState;

/**
 * result Outbox dispatcher 的一次本地推进结果。
 *
 * @param eventId    result event 身份
 * @param state      本地推进后的状态
 * @param httpStatus 最近一次 HTTP 状态，可为空
 * @param errorCode  低基数错误码，可为空
 */
public record SagaResultOutboxDispatchResult(
        String eventId,
        SagaResultOutboxState state,
        Integer httpStatus,
        String errorCode) {
}
