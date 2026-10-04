package io.github.nasaruntime.saga.mybatis;

import io.github.nasaruntime.saga.SagaProtocolException;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 持锁记录构造失败的确定性分类，不把基础设施故障转为人工处理状态。
 */
final class SagaStoredContract {

    /**
     * 业务作用：禁止构造无状态的持久化合同分类器。
     * 参数说明：无。
     * 返回：仅供类内部使用。
     */
    private SagaStoredContract() {}

    /**
     * 业务作用：仅识别构造器明确报告的内容合同异常，任何 SQL 原因都保留事务失败语义。
     *
     * @param failure MyBatis 构造记录产生的异常链
     * @return 存在协议异常且整条原因链没有 SQL 异常时为 true；未知错误不隔离。
     */
    static boolean invalid(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean protocol = false;
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SQLException) return false;
            if (current instanceof SagaProtocolException) protocol = true;
        }
        return protocol;
    }
}
