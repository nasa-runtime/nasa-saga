package io.github.nasaruntime.saga;

/**
 * HTTP participant 共享 replay claim 的持久化 SPI。
 *
 * <p>实现必须把 {@code (producer, nonce)} 作为唯一键原子占用；不能使用单 JVM 内存集合，
 * 否则多副本或进程重启会让同一签名重复进入业务事务。</p>
 */
@FunctionalInterface
public interface SagaHttpReplayClaimStore {

    /**
     * 业务作用：在验签成功后原子占用一次性 nonce，阻止重复请求进入 JSON 解码和业务事务。
     *
     * @param producer 已通过 HMAC 的逻辑 producer
     * @param nonce 已通过格式校验的一次性随机数
     * @param expiresAtMillis Rust 时间窗对应的 claim 过期时刻
     * @return 首次占用返回 {@code true}；数据库唯一键命中返回 {@code false}
     * @throws Exception 持久化不可用，调用方必须返回 503
     */
    boolean claim(String producer, String nonce, long expiresAtMillis) throws Exception;
}
