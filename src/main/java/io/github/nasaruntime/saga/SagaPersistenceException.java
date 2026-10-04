package io.github.nasaruntime.saga;

/**
 * Java 本地 Saga 持久化不可用异常。
 */
public final class SagaPersistenceException extends RuntimeException {

    /**
     * 业务作用：把数据库连接、提交和条件更新失败收口为可靠 dispatcher 可识别的本地错误。
     *
     * @param message 脱敏描述
     * @param cause 底层持久化异常
     */
    public SagaPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
