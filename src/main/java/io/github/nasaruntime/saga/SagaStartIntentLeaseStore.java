package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.mybatis.SagaIntentState;
import io.github.nasaruntime.saga.rc.SagaStartIntent;

/**
 * reliable start dispatcher 的短事务 lease/fencing SPI。
 */
public interface SagaStartIntentLeaseStore {

    /**
     * 业务作用：按 intent 身份观测持久 body 和当前状态；读取结果不能授予 dispatcher 本次执行权威。
     *
     * @param intentId intent 身份
     * @return 本地记录，不存在时为 {@code null}
     */
    SagaStartIntent find(String intentId);

    /**
     * 业务作用：在独立短事务中领取待发送 intent，领取提交后才允许执行远程 HTTP。
     *
     * @param intentId     intent 身份
     * @param owner        worker 身份
     * @param nowMs        当前时刻
     * @param leaseUntilMs lease 到期时刻
     * @return 在领取事务内读取、提交成功后返回的不可变快照；未领取或已条件隔离坏记录为 {@code null}。
     * 不得提交后另查当前 token；隔离必须保留原正文和摘要，且遵守同一行锁、token 与数据库 lease。
     */
    SagaStartIntent claim(String intentId, String owner, long nowMs, long leaseUntilMs);

    /**
     * 业务作用：以 owner、token 和未到期 lease 条件提交结果，阻止失权 worker 覆盖新结果。
     *
     * @param intentId        intent 身份
     * @param owner           当前 worker
     * @param fencingToken    当前 fencing token
     * @param state           新状态
     * @param statusCode      最近 HTTP 状态
     * @param errorCode       低基数错误码
     * @param nextAttemptAtMs 下一次尝试时刻
     * @param nowMs           更新时间
     * @return 当前 worker 成功回写为 {@code true}
     */
    boolean settle(
            String intentId,
            String owner,
            long fencingToken,
            SagaIntentState state,
            Integer statusCode,
            String errorCode,
            Long nextAttemptAtMs,
            long nowMs);
}
