package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaReliableStartReceipt;
import io.github.nasaruntime.saga.rc.SagaStartRequest;

import io.github.nasaruntime.saga.mybatis.SagaIntentState;
import io.github.nasaruntime.saga.rc.SagaStartIntent;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Java reliable start 的业务边界。
 *
 * <p>调用方应先写业务事实，再通过同一个本地事务的 {@link SagaStartIntentAppender} 追加 intent；
 * 本类不会在事务内访问 Rust，也不会把本地受理返回为远端 Committed。</p>
 */
public final class ReliableSagaStarter {

    private final SagaStartIntentAppender appender;
    private final Clock clock;

    /**
     * 业务作用：绑定业务事务使用的本地 intent appender 和时间源。
     *
     * @param appender 必须与业务 mapper 共用同一事务的写入器
     * @param clock 本地时间源
     */
    public ReliableSagaStarter(SagaStartIntentAppender appender, Clock clock) {
        this.appender = Objects.requireNonNull(appender, "appender");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 业务作用：冻结 start body、业务槽位摘要和 lease 初始状态，并把 intent 交给调用方事务提交。
     *
     * @param request Rust start 请求
     * @return 本地 PENDING 受理收据
     * @throws SagaPersistenceException intent 写入失败，调用方必须回滚业务事务
     */
    public SagaReliableStartReceipt submit(SagaStartRequest request) {
        Objects.requireNonNull(request, "request");
        request.validate();
        byte[] body = SagaHttpStartCodec.encode(request);
        long nowMs = clock.millis();
        if (nowMs < 0) {
            throw new SagaProtocolException("clock is before Unix epoch");
        }
        String intentId = UUID.randomUUID().toString();
        SagaStartIntent intent = new SagaStartIntent(
                intentId,
                request.tenantId(),
                request.workflow(),
                request.businessKey(),
                request.sagaId(),
                request.definitionVersion(),
                request.expectedDefinitionDigest() == null || request.expectedDefinitionDigest().isEmpty()
                        ? null : request.expectedDefinitionDigest(),
                request.triggerId(),
                request.deadline() == null ? null : request.deadline().toEpochMilli(),
                SagaTraceparent.validOrNull(request.traceparent()),
                body,
                SagaHttpStartCodec.sha256Hex(body),
                SagaHttpStartCodec.tupleDigest(request.tenantId(), request.workflow(), request.businessKey()),
                SagaHttpStartCodec.tupleDigest(request.tenantId(), request.sagaId()),
                SagaIntentState.PENDING,
                0,
                nowMs,
                null,
                null,
                0,
                null,
                null,
                nowMs,
                nowMs);
        try {
            appender.append(intent);
        } catch (Exception exception) {
            throw new SagaPersistenceException("Saga start intent could not be persisted", exception);
        }
        return new SagaReliableStartReceipt(intentId, SagaIntentState.PENDING);
    }
}
