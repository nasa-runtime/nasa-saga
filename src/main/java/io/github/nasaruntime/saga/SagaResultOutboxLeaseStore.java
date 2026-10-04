package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaResultOutbox;
import io.github.nasaruntime.saga.mybatis.SagaResultOutboxState;

/**
 * participant result Outbox 的短事务 lease/fencing SPI。
 */
public interface SagaResultOutboxLeaseStore {

    /**
     * 业务作用：按稳定 event_id 观测 result 正文和状态；读取结果不能代替本次领取快照。
     *
     * @param eventId result event 身份
     * @return 本地记录，不存在时为 {@code null}
     */
    SagaResultOutbox find(String eventId);

    /**
     * 业务作用：在独立短事务中领取可发送事件，提交后才允许访问 Rust 网络。
     *
     * @param eventId      result event 身份
     * @param owner        当前 worker 身份
     * @param nowMs        当前时刻
     * @param leaseUntilMs 本次 lease 到期时刻
     * @return 在领取事务内读取、提交成功后返回的不可变快照；未领取或已持锁隔离损坏记录为 {@code null}。
     * 不得提交后另查当前 token；隔离必须保留正文和低敏原因，不授予调用方网络执行资格。
     */
    SagaResultOutbox claim(String eventId, String owner, long nowMs, long leaseUntilMs);

    /**
     * 业务作用：使用 owner、token 和未到期 lease 条件提交收据，防止旧 worker 覆盖新裁决。
     *
     * @param eventId         result event 身份
     * @param owner           当前 worker 身份
     * @param fencingToken    当前 fencing token
     * @param state           新的 Outbox 状态
     * @param nextAttemptAtMs 下一次尝试时间；终态为空
     * @return 当前 worker 成功回写为 {@code true}
     */
    boolean settle(
            String eventId,
            String owner,
            long fencingToken,
            SagaResultOutboxState state,
            Long nextAttemptAtMs);

    /**
     * 业务作用：在同一次 fencing 回写中保存投递状态与低敏原因，供人工处置确定拒绝。
     *
     * @param eventId         result event 身份
     * @param owner           当前 worker 身份
     * @param fencingToken    领取事务冻结的 token
     * @param state           新的 Outbox 状态
     * @param nextAttemptAtMs 下一次尝试时间；终态为空
     * @param errorCode       固定低敏分类码，成功时为空；不得传入异常正文
     * @return 当前 worker 成功回写为 true；默认兼容已有 SPI，仅保存状态，存储实现应覆写以保存原因。
     */
    default boolean settle(String eventId, String owner, long fencingToken,
                           SagaResultOutboxState state, Long nextAttemptAtMs, String errorCode) {
        return settle(eventId, owner, fencingToken, state, nextAttemptAtMs);
    }
}
