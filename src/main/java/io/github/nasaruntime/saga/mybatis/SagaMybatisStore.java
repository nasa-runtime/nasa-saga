package io.github.nasaruntime.saga.mybatis;

import org.apache.ibatis.session.SqlSession;

import java.util.Objects;

/**
 * 绑定宿主 SqlSession 的 Java Saga 本地持久化门面。
 *
 * <p>实例不拥有事务和连接生命周期；业务应用必须让业务 mapper、Saga mapper 和本地事实共享同一个
 * {@link SqlSession}，并由外层在网络调用之外提交或回滚。</p>
 */
public final class SagaMybatisStore {

    private final SqlSession session;
    private final SagaMybatisDialect dialect;
    private final SagaMybatisMapper mapper;

    /**
     * 业务作用：把当前业务事务使用的 SqlSession 绑定为 Saga 本地数据面的唯一 SQL 入口。
     *
     * @param session 由宿主创建并管理事务的 MyBatis session
     * @param dialect 当前数据库方言
     */
    public SagaMybatisStore(SqlSession session, SagaMybatisDialect dialect) {
        this.session = Objects.requireNonNull(session, "session");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.mapper = session.getMapper(SagaMybatisMapper.class);
    }

    /**
     * 业务作用：返回绑定 session 的 mapper，供业务应用在同一事务内组合本地事实与 Saga SQL。
     *
     * @return MyBatis mapper
     */
    public SagaMybatisMapper mapper() {
        return mapper;
    }

    /**
     * 业务作用：返回当前 SQL 方言，确保 replay claim 使用与数据库一致的唯一写入语句。
     *
     * @return SQL 方言
     */
    public SagaMybatisDialect dialect() {
        return dialect;
    }

    /**
     * 业务作用：声明当前 session 仍由宿主持有，门面不主动提交或关闭连接。
     *
     * @return 宿主拥有的 SqlSession
     */
    public SqlSession session() {
        return session;
    }

    /**
     * 业务作用：在 command/result 事务边界前原子占用 HTTP nonce。
     *
     * @param producer    逻辑 producer
     * @param nonce       nonce
     * @param expiresAtMs 过期时刻
     * @return 首次占用为 1，重复为 0
     */
    public int claimReplay(String producer, String nonce, long expiresAtMs) {
        return mapper.claimReplay(dialect, producer, nonce, expiresAtMs);
    }

    /**
     * 业务作用：有界清理过期 nonce，避免认证表无限增长。
     *
     * @param nowMs 当前时刻
     * @param limit 单轮上限
     * @return 删除行数
     */
    public int purgeReplay(long nowMs, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return mapper.purgeReplay(dialect, nowMs, limit);
    }
}
