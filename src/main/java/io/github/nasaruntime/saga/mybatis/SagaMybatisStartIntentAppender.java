package io.github.nasaruntime.saga.mybatis;

import io.github.nasaruntime.saga.rc.SagaStartIntent;

import io.github.nasaruntime.saga.SagaPersistenceException;
import io.github.nasaruntime.saga.SagaStartIntentAppender;

import java.util.Objects;

/**
 * 使用宿主当前 SqlSession 写入 start intent 的 appender。
 */
public final class SagaMybatisStartIntentAppender implements SagaStartIntentAppender {

    private final SagaMybatisMapper mapper;

    /**
     * 业务作用：让业务事实 mapper 与 start intent mapper 共享同一 SqlSession 和事务。
     *
     * @param store 当前业务事务绑定的 Saga MyBatis store
     */
    public SagaMybatisStartIntentAppender(SagaMybatisStore store) {
        this.mapper = Objects.requireNonNull(store, "store").mapper();
    }

    /**
     * 业务作用：在调用方当前事务中追加 intent；本方法不提交事务，确保业务事实与 intent 同生共死。
     *
     * @param intent 已冻结的 intent
     */
    @Override
    public void append(SagaStartIntent intent) {
        try {
            if (mapper.insertStartIntent(Objects.requireNonNull(intent, "intent")) != 1) {
                throw new SagaPersistenceException("Saga start intent insert did not affect one row", null);
            }
        } catch (SagaPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SagaPersistenceException("Saga start intent insert failed", exception);
        }
    }
}
