package io.github.nasaruntime.saga.mybatis;

import io.github.nasaruntime.saga.SagaHttpReplayClaimStore;
import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaPersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Clock;
import java.util.Objects;

/**
 * 使用 MyBatis 维护与 Rust 共享的 HTTP replay claim。
 *
 * <p>claim 与过期回收在同一个短事务中完成；业务 command 事务必须在 claim 成功后另行开始，
 * 这样认证证据不会因为业务回滚而再次可用。</p>
 */
public final class SagaMybatisHttpReplayClaimStore implements SagaHttpReplayClaimStore {

    private final SqlSessionFactory sessionFactory;
    private final SagaMybatisDialect dialect;
    private final Clock clock;
    private final int purgeLimit;

    /**
     * 业务作用：绑定宿主 MyBatis 工厂和数据库方言，建立跨副本共享的一次性 nonce 门禁。
     *
     * @param sessionFactory 宿主创建的 MyBatis session factory
     * @param dialect        当前 replay 表所在数据库方言
     */
    public SagaMybatisHttpReplayClaimStore(
            SqlSessionFactory sessionFactory,
            SagaMybatisDialect dialect) {
        this(sessionFactory, dialect, Clock.systemUTC(), 256);
    }

    /**
     * 业务作用：为本地测试或统一时钟装配 replay claim 存储，并固定每次回收的最大行数。
     *
     * @param sessionFactory 宿主创建的 MyBatis session factory
     * @param dialect        当前 replay 表所在数据库方言
     * @param clock          用于过期证据回收的 Unix 时钟
     * @param purgeLimit     单次回收上限
     */
    public SagaMybatisHttpReplayClaimStore(
            SqlSessionFactory sessionFactory,
            SagaMybatisDialect dialect,
            Clock clock,
            int purgeLimit) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (purgeLimit <= 0) {
            throw new IllegalArgumentException("purgeLimit must be positive");
        }
        this.purgeLimit = purgeLimit;
    }

    /**
     * 业务作用：先回收已越过签名窗口的证据，再以数据库唯一键原子占用当前 producer/nonce。
     *
     * @param producer        已通过 HMAC 验证的逻辑 producer
     * @param nonce           已通过 HMAC 验证的一次性 nonce
     * @param expiresAtMillis 签名窗口闭区间的过期时刻
     * @return 首次占用为 {@code true}；同一窗口内重复为 {@code false}
     */
    @Override
    public boolean claim(String producer, String nonce, long expiresAtMillis) {
        SagaIds.requireStructured(producer, "saga_producer");
        if (!lowerHex(nonce, 32) || expiresAtMillis < 0) {
            throw new IllegalArgumentException("replay claim fields are invalid");
        }
        long nowMs = clock.millis();
        if (nowMs < 0) {
            throw new SagaPersistenceException("replay claim clock is before Unix epoch", null);
        }
        try (SqlSession session = sessionFactory.openSession(false)) {
            SagaMybatisMapper mapper = session.getMapper(SagaMybatisMapper.class);
            mapper.purgeReplay(dialect, nowMs, purgeLimit);
            boolean accepted = mapper.claimReplay(dialect, producer, nonce, expiresAtMillis) == 1;
            session.commit();
            return accepted;
        } catch (RuntimeException exception) {
            throw new SagaPersistenceException("HTTP replay claim persistence failed", exception);
        }
    }

    /**
     * 业务作用：限定 replay 身份的规范表示，防止等价文本绕过持久唯一键。
     *
     * @param value  待校验文本
     * @param length 合同规定的字符数
     * @return 仅精确长度的小写十六进制值成立。
     */
    private static boolean lowerHex(String value, int length) {
        return value != null && value.length() == length
                && value.chars().allMatch(character -> character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f');
    }
}
