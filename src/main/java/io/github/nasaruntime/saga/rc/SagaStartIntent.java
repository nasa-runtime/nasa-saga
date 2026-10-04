package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.mybatis.SagaIntentState;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;
import io.github.nasaruntime.saga.SagaTraceparent;

/**
 * Java reliable start intent 的本地持久记录。
 *
 * @param intentId                 本地 intent 身份，必须为小写规范 UUID，不参与远端 Saga 身份派生
 * @param tenantId                 远端租户身份
 * @param workflow                 远端 workflow
 * @param businessKey              Rust 去重槽位中的业务键
 * @param sagaId                   固定的远端 Saga 身份
 * @param definitionVersion        固定的 definition 版本
 * @param expectedDefinitionDigest 可选的摘要前置条件
 * @param triggerId                固定的发起身份
 * @param deadlineAtMs             远端业务期限，可为空
 * @param traceparent              固定传播的链路上下文，可为空
 * @param requestBody              最终发送的 raw JSON body
 * @param requestSha256            raw body 的 SHA-256 文本摘要
 * @param businessSlotDigest       本地业务槽位摘要
 * @param sagaIdDigest             本地 Saga 身份摘要
 * @param state                    投递状态
 * @param attempts                 已领取次数
 * @param nextAttemptAtMs          下次领取时刻
 * @param leaseOwner               当前 dispatcher 身份
 * @param leaseUntilMs             当前 lease 到期时刻
 * @param fencingToken             当前 fencing token
 * @param lastHttpStatus           最近一次 HTTP 状态，可为空
 * @param lastErrorCode            最近一次低基数错误，可为空
 * @param createdAtMs              本地创建时刻
 * @param updatedAtMs              本地更新时间
 */
public record SagaStartIntent(
        String intentId,
        String tenantId,
        String workflow,
        String businessKey,
        String sagaId,
        int definitionVersion,
        String expectedDefinitionDigest,
        String triggerId,
        Long deadlineAtMs,
        String traceparent,
        byte[] requestBody,
        String requestSha256,
        byte[] businessSlotDigest,
        byte[] sagaIdDigest,
        SagaIntentState state,
        int attempts,
        Long nextAttemptAtMs,
        String leaseOwner,
        Long leaseUntilMs,
        long fencingToken,
        Integer lastHttpStatus,
        String lastErrorCode,
        long createdAtMs,
        long updatedAtMs) {

    /**
     * 业务作用：在写入或领取快照构造时统一验证本地身份与冻结事实，非法记录不得取得远端发送资格。
     * 参数说明：各显式参数对应记录字段合同，其中 intentId 必须为小写规范 UUID。
     * 返回：校验通过后冻结正文和摘要副本；非法身份或持久化字段抛出协议异常，领取方可据此持锁隔离。
     */
    public SagaStartIntent {
        // 本地投递键在正常写入前即满足同一合同；损坏存量行由领取事务隔离，不能重新生成身份后发送。
        SagaIds.requireUuid(intentId, "intent_id");
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        SagaIds.requireStructured(workflow, "workflow");
        SagaIds.requireOpaque(businessKey, "business_key", 256);
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        SagaIds.requirePositive(definitionVersion, "definition_version");
        if (expectedDefinitionDigest != null) {
            SagaIds.requireDigest(expectedDefinitionDigest);
        }
        SagaIds.requireOpaque(triggerId, "trigger_id", 190);
        if (traceparent != null && SagaTraceparent.validOrNull(traceparent) == null) {
            throw new SagaProtocolException("traceparent is invalid");
        }
        if (requestBody == null || requestBody.length == 0) {
            throw new SagaProtocolException("start intent request body is required");
        }
        SagaIds.requireDigest(requestSha256);
        requireDigestBytes(businessSlotDigest, "business_slot_digest");
        requireDigestBytes(sagaIdDigest, "saga_id_digest");
        if (state == null || attempts < 0 || fencingToken < 0 || createdAtMs < 0 || updatedAtMs < 0
                || (nextAttemptAtMs != null && nextAttemptAtMs < 0)
                || (leaseUntilMs != null && leaseUntilMs < 0)) {
            throw new SagaProtocolException("start intent state counters are invalid");
        }
        if (leaseOwner != null) {
            SagaIds.requireOpaque(leaseOwner, "lease_owner", 256);
        }
        if (lastErrorCode != null) {
            SagaIds.requireReasonCode(lastErrorCode);
        }
        requestBody = requestBody.clone();
        businessSlotDigest = businessSlotDigest.clone();
        sagaIdDigest = sagaIdDigest.clone();
    }

    /**
     * 业务作用：提供原始 start body 的不可变副本，使每次 HTTP 重试保持同一字节语义。
     *
     * @return raw body 副本
     */
    @Override
    public byte[] requestBody() {
        return requestBody.clone();
    }

    /**
     * 业务作用：返回本地业务槽位索引摘要，不让调用方修改唯一约束依据。
     *
     * @return 槽位摘要副本
     */
    @Override
    public byte[] businessSlotDigest() {
        return businessSlotDigest.clone();
    }

    /**
     * 业务作用：返回本地 Saga 身份索引摘要，不让调用方修改冲突检测依据。
     *
     * @return Saga 摘要副本
     */
    @Override
    public byte[] sagaIdDigest() {
        return sagaIdDigest.clone();
    }

    /**
     * 业务作用：确保本地唯一槽位与 Saga 索引使用完整 SHA-256，而非截断或缺失摘要。
     *
     * @param value 冻结的摘要字节
     * @param field 摘要所属字段，仅用于低敏失败分类
     *              返回：完整 32 字节摘要原样通过；其它长度抛出协议异常。
     */
    private static void requireDigestBytes(byte[] value, String field) {
        if (value == null || value.length != 32) {
            throw new SagaProtocolException(field + " must contain 32 bytes");
        }
    }
}
