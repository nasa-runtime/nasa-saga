package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.mybatis.SagaResultOutboxState;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

/**
 * Java participant result Outbox 的持久记录。
 *
 * @param eventId         result event 身份
 * @param sagaId          Saga 身份
 * @param commandId       原 command 身份
 * @param payload         最终发送的原始 result JSON
 * @param traceparent     固定链路上下文，可为空
 * @param state           Outbox 状态
 * @param attempts        领取次数
 * @param nextAttemptAtMs 下次领取时刻
 * @param leaseOwner      当前 dispatcher 身份
 * @param leaseUntilMs    当前 lease 到期时刻
 * @param fencingToken    当前 fencing token
 */
public record SagaResultOutbox(
        String eventId,
        String sagaId,
        String commandId,
        byte[] payload,
        String traceparent,
        SagaResultOutboxState state,
        int attempts,
        Long nextAttemptAtMs,
        String leaseOwner,
        Long leaseUntilMs,
        long fencingToken) {

    /**
     * 业务作用：冻结 result event 和原始正文，确保网络未知结果后的重投仍指向同一业务事实。
     */
    public SagaResultOutbox {
        SagaIds.requireUuid(eventId, "event_id");
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        SagaIds.requireUuid(commandId, "command_id");
        if (payload == null || payload.length == 0) {
            throw new SagaProtocolException("result outbox payload is required");
        }
        if (state == null || attempts < 0 || fencingToken < 0) {
            throw new SagaProtocolException("result outbox state is invalid");
        }
        if (traceparent != null && SagaIds.utf8Length(traceparent) > 55) {
            throw new SagaProtocolException("traceparent is too long");
        }
        SagaResultEnvelope result = SagaResultEnvelope.decode(payload);
        if (!eventId.equals(result.eventId()) || !sagaId.equals(result.sagaId())
                || !commandId.equals(result.commandId())) {
            throw new SagaProtocolException("result outbox identity does not match payload");
        }
        payload = payload.clone();
    }

    /**
     * 业务作用：返回 result 原始正文副本，保证重试不发生重新序列化。
     *
     * @return payload 副本
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
