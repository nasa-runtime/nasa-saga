package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaStartIntent;

/**
 * 业务事实事务中的 start-intent 写入 SPI。
 */
@FunctionalInterface
public interface SagaStartIntentAppender {

    /**
     * 业务作用：把 start intent 与业务事实写入同一个本地事务，提交后才允许 dispatcher 发送网络请求。
     *
     * @param intent 已冻结 raw body、业务槽位摘要和远端身份的记录
     * @throws Exception 事务写入失败，业务事实与 intent 必须一起回滚
     */
    void append(SagaStartIntent intent) throws Exception;
}
