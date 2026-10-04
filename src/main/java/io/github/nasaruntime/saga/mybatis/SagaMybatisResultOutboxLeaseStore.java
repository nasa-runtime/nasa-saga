package io.github.nasaruntime.saga.mybatis;

import io.github.nasaruntime.saga.rc.SagaResultOutbox;

import io.github.nasaruntime.saga.SagaPersistenceException;
import io.github.nasaruntime.saga.SagaResultOutboxLeaseStore;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.Objects;

/**
 * 使用 MyBatis 为 participant result Outbox 提供独立短事务的 lease/fencing 操作。
 */
public final class SagaMybatisResultOutboxLeaseStore implements SagaResultOutboxLeaseStore {

    private final SqlSessionFactory sessionFactory;

    /**
     * 业务作用：绑定宿主 session factory，使 result 网络调用不占用数据库事务。
     *
     * @param sessionFactory 宿主 MyBatis session factory
     */
    public SagaMybatisResultOutboxLeaseStore(SqlSessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    /**
     * 业务作用：读取已提交的 result Outbox 事实，供 dispatcher 复用原始 event 身份。
     *
     * @param eventId result event 身份
     * @return 本地记录或 {@code null}
     */
    @Override
    public SagaResultOutbox find(String eventId) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            return session.getMapper(SagaMybatisMapper.class).findResultOutbox(eventId);
        } catch (RuntimeException exception) {
            throw new SagaPersistenceException("result Outbox lookup failed", exception);
        }
    }

    /**
     * 业务作用：提交 result Outbox lease 和 fencing token，提交后才允许访问 Rust 网络。
     *
     * @param eventId      result event 身份
     * @param owner        worker 身份
     * @param nowMs        当前时刻
     * @param leaseUntilMs lease 到期时刻
     * @return 本次领取的不可变快照；未领取或持锁隔离了不合法记录时为 {@code null}。
     */
    @Override
    public SagaResultOutbox claim(String eventId, String owner, long nowMs, long leaseUntilMs) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            SagaMybatisMapper mapper = session.getMapper(SagaMybatisMapper.class);
            int updated = mapper.claimResultOutbox(eventId, owner, nowMs, leaseUntilMs);
            if (updated == 0) {
                session.commit();
                return null;
            }
            // 在领取事务释放行锁前冻结 token，owner 重用不会把新租约授予旧执行流。
            Long token = mapper.findResultFencingToken(eventId);
            if (updated != 1 || token == null)
                throw new SagaPersistenceException("result claim identity unavailable", null);
            SagaResultOutbox claimed;
            try {
                claimed = mapper.findResultOutbox(eventId);
                if (claimed == null) throw new SagaPersistenceException("claimed result unavailable", null);
            } catch (RuntimeException failure) {
                if (!SagaStoredContract.invalid(failure)) throw failure;
                // 正文构造失败不代表数据库不可用；凭本事务行锁与 token 隔离，保留原 bytes 供人工核对。
                // 数据库、映射装配等其它失败仍回滚，不能把暂时故障冒充持久化内容损坏。
                int isolated = mapper.settleResultOutbox(eventId, owner, token,
                        SagaResultOutboxState.NEEDS_ATTENTION, null,
                        SagaMybatisDialect.from(session.getConnection()), "local_result_contract_invalid");
                if (isolated != 1) throw new SagaPersistenceException("result lease lost before isolation", null);
                session.commit();
                return null;
            }
            session.commit();
            return claimed;
        } catch (RuntimeException exception) {
            throw new SagaPersistenceException("result Outbox claim failed", exception);
        }
    }

    /**
     * 业务作用：以 fencing 条件提交远端收据分类，失权 worker 不得覆盖新投递结果。
     *
     * @param eventId         result event 身份
     * @param owner           worker 身份
     * @param fencingToken    fencing token
     * @param state           新状态
     * @param nextAttemptAtMs 下一次尝试时刻；终态为空
     * @return 当前 worker 成功回写为 {@code true}
     */
    @Override
    public boolean settle(
            String eventId,
            String owner,
            long fencingToken,
            SagaResultOutboxState state,
            Long nextAttemptAtMs) {
        return settle(eventId, owner, fencingToken, state, nextAttemptAtMs,
                state == SagaResultOutboxState.NEEDS_ATTENTION ? "result_delivery_rejected" : null);
    }

    /**
     * 业务作用：在未到期的原租约内原子保存状态与低敏原因，不修改已经冻结的 result 正文。
     *
     * @param eventId         result event 身份
     * @param owner           worker 身份
     * @param fencingToken    领取时冻结的 token
     * @param state           新状态
     * @param nextAttemptAtMs 下一次尝试时刻；终态为空
     * @param errorCode       固定分类码，成功时为空
     * @return 仍持有当前 lease 的 worker 成功回写为 true。
     */
    @Override
    public boolean settle(String eventId, String owner, long fencingToken,
                          SagaResultOutboxState state, Long nextAttemptAtMs, String errorCode) {
        if (errorCode != null && !errorCode.matches("[a-z][a-z0-9_]{0,95}")) {
            throw new IllegalArgumentException("invalid result error code");
        }
        try (SqlSession session = sessionFactory.openSession(false)) {
            int updated = session.getMapper(SagaMybatisMapper.class).settleResultOutbox(
                    eventId, owner, fencingToken, state, nextAttemptAtMs,
                    SagaMybatisDialect.from(session.getConnection()), errorCode);
            session.commit();
            return updated == 1;
        } catch (RuntimeException exception) {
            throw new SagaPersistenceException("result Outbox settlement failed", exception);
        }
    }

}
