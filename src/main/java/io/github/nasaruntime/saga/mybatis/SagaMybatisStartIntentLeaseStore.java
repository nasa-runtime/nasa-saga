package io.github.nasaruntime.saga.mybatis;

import io.github.nasaruntime.saga.rc.SagaStartIntent;

import io.github.nasaruntime.saga.SagaPersistenceException;
import io.github.nasaruntime.saga.SagaStartIntentLeaseStore;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.Objects;

/**
 * 为 reliable dispatcher 提供每次操作独立短事务的 MyBatis lease/fencing store。
 */
public final class SagaMybatisStartIntentLeaseStore implements SagaStartIntentLeaseStore {

    private final SqlSessionFactory sessionFactory;

    /**
     * 业务作用：绑定宿主的 SqlSessionFactory，使 claim/settle 不会把网络调用放进数据库事务。
     *
     * @param sessionFactory 业务应用配置的 MyBatis session factory
     */
    public SagaMybatisStartIntentLeaseStore(SqlSessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    /**
     * 业务作用：在短查询中读取已提交 intent，供 dispatcher 使用冻结 raw body。
     *
     * @param intentId intent 身份
     * @return 本地记录或 {@code null}
     */
    @Override
    public SagaStartIntent find(String intentId) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            return session.getMapper(SagaMybatisMapper.class).findStartIntent(intentId);
        } catch (RuntimeException exception) {
            throw new SagaPersistenceException("Saga start intent lookup failed", exception);
        }
    }

    /**
     * 业务作用：在独立事务中提交 lease 和 fencing token，提交成功后才允许执行 HTTP。
     *
     * @param intentId     intent 身份
     * @param owner        worker 身份
     * @param nowMs        当前时刻
     * @param leaseUntilMs lease 到期时刻
     * @return 本次领取的不可变快照；未领取或已持锁隔离不合法记录时为 {@code null}
     */
    @Override
    public SagaStartIntent claim(String intentId, String owner, long nowMs, long leaseUntilMs) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            SagaMybatisMapper mapper = session.getMapper(SagaMybatisMapper.class);
            int updated = mapper.claimStartIntent(intentId, owner, nowMs, leaseUntilMs);
            if (updated == 0) {
                session.commit();
                return null;
            }
            // 领取行锁覆盖 token 与快照读取；坏正文不能迫使每次领取回滚并永久占据恢复窗口。
            Long token = mapper.findStartFencingToken(intentId);
            if (updated != 1 || token == null)
                throw new SagaPersistenceException("start claim identity unavailable", null);
            SagaStartIntent claimed;
            try {
                claimed = mapper.findStartIntent(intentId);
                if (claimed == null) throw new SagaPersistenceException("claimed start unavailable", null);
            } catch (RuntimeException failure) {
                if (!SagaStoredContract.invalid(failure)) throw failure;
                // 只凭本次 owner/token 和数据库未到期 lease 隔离，保留原正文、摘要和业务身份供人工核对。
                int isolated = mapper.settleStartIntent(intentId, owner, token, SagaIntentState.NEEDS_ATTENTION,
                        null, "local_request_contract_invalid", null, System.currentTimeMillis(),
                        SagaMybatisDialect.from(session.getConnection()));
                if (isolated != 1) throw new SagaPersistenceException("start lease lost before isolation", null);
                session.commit();
                return null;
            }
            session.commit();
            return claimed;
        } catch (RuntimeException exception) {
            throw new SagaPersistenceException("Saga start intent claim failed", exception);
        }
    }

    /**
     * 业务作用：以 fencing 条件提交远端裁决或待重试状态，失权 worker 不能覆盖新结果。
     *
     * @param intentId        intent 身份
     * @param owner           worker 身份
     * @param fencingToken    fencing token
     * @param state           新状态
     * @param statusCode      最近 HTTP 状态
     * @param errorCode       低基数错误码
     * @param nextAttemptAtMs 下一次尝试时间
     * @param nowMs           调用方携带的调度时间；实际更新时间采用回写执行时刻，不用它延长 lease
     * @return 是否成功回写
     */
    @Override
    public boolean settle(
            String intentId,
            String owner,
            long fencingToken,
            SagaIntentState state,
            Integer statusCode,
            String errorCode,
            Long nextAttemptAtMs,
            long nowMs) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            int updated = session.getMapper(SagaMybatisMapper.class).settleStartIntent(
                    intentId, owner, fencingToken, state, statusCode, errorCode, nextAttemptAtMs,
                    System.currentTimeMillis(), SagaMybatisDialect.from(session.getConnection()));
            session.commit();
            return updated == 1;
        } catch (RuntimeException exception) {
            throw new SagaPersistenceException("Saga start intent settlement failed", exception);
        }
    }
}
