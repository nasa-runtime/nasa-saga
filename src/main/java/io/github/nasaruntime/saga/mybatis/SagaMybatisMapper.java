package io.github.nasaruntime.saga.mybatis;

import io.github.nasaruntime.saga.rc.SagaParticipantGate;
import io.github.nasaruntime.saga.rc.SagaParticipantInbox;
import io.github.nasaruntime.saga.rc.SagaResultOutbox;
import io.github.nasaruntime.saga.rc.SagaStartIntent;

import io.github.nasaruntime.saga.rc.SagaStepResult;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;

/**
 * Java Saga 本地数据面的 MyBatis mapper。
 *
 * <p>所有方法都要求调用方把业务 mapper 与本 mapper 放入同一个 {@code SqlSession} 和数据库事务；
 * mapper 不自行开启连接、提交事务或发起网络调用。</p>
 */
public interface SagaMybatisMapper {

    /**
     * 业务作用：把 start intent 与业务事实一起写入本地可靠发起表，唯一摘要冲突必须回滚业务事务。
     *
     * @param intent 要持久化的 start intent
     * @return 插入行数
     */
    @Insert("""
            INSERT INTO saga_start_intent (
                intent_id, tenant_id, workflow, business_key, saga_id, definition_version,
                expected_definition_digest, trigger_id, deadline_at_ms, request_body, request_sha256,
                traceparent,
                business_slot_digest, saga_id_digest, state, attempts, next_attempt_at_ms,
                lease_owner, lease_until_ms, fencing_token, last_http_status, last_error_code,
                created_at_ms, updated_at_ms
            ) VALUES (
                #{intent.intentId}, #{intent.tenantId}, #{intent.workflow}, #{intent.businessKey},
                #{intent.sagaId}, #{intent.definitionVersion}, #{intent.expectedDefinitionDigest},
                #{intent.triggerId}, #{intent.deadlineAtMs}, #{intent.requestBody}, #{intent.requestSha256},
                #{intent.traceparent},
                #{intent.businessSlotDigest}, #{intent.sagaIdDigest}, #{intent.state}, #{intent.attempts},
                #{intent.nextAttemptAtMs}, #{intent.leaseOwner}, #{intent.leaseUntilMs},
                #{intent.fencingToken}, #{intent.lastHttpStatus}, #{intent.lastErrorCode},
                #{intent.createdAtMs}, #{intent.updatedAtMs}
            )
            """)
    int insertStartIntent(@Param("intent") SagaStartIntent intent);

    /**
     * 业务作用：按本地 intent 身份读取冻结的 start body 和状态，供 dispatcher 恢复投递。
     *
     * @param intentId 本地 intent 身份
     * @return 持久记录，不存在时为 {@code null}
     */
    @Select("""
            SELECT intent_id, tenant_id, workflow, business_key, saga_id, definition_version,
                   expected_definition_digest, trigger_id, deadline_at_ms, traceparent, request_body, request_sha256,
                   business_slot_digest, saga_id_digest, state, attempts, next_attempt_at_ms,
                   lease_owner, lease_until_ms, fencing_token, last_http_status, last_error_code,
                   created_at_ms, updated_at_ms
            FROM saga_start_intent
            WHERE intent_id = #{intentId}
            """)
    @ConstructorArgs({
            @Arg(column = "intent_id", javaType = String.class),
            @Arg(column = "tenant_id", javaType = String.class),
            @Arg(column = "workflow", javaType = String.class),
            @Arg(column = "business_key", javaType = String.class),
            @Arg(column = "saga_id", javaType = String.class),
            @Arg(column = "definition_version", javaType = int.class),
            @Arg(column = "expected_definition_digest", javaType = String.class),
            @Arg(column = "trigger_id", javaType = String.class),
            @Arg(column = "deadline_at_ms", javaType = Long.class),
            @Arg(column = "traceparent", javaType = String.class),
            @Arg(column = "request_body", javaType = byte[].class),
            @Arg(column = "request_sha256", javaType = String.class),
            @Arg(column = "business_slot_digest", javaType = byte[].class),
            @Arg(column = "saga_id_digest", javaType = byte[].class),
            @Arg(column = "state", javaType = SagaIntentState.class),
            @Arg(column = "attempts", javaType = int.class),
            @Arg(column = "next_attempt_at_ms", javaType = Long.class),
            @Arg(column = "lease_owner", javaType = String.class),
            @Arg(column = "lease_until_ms", javaType = Long.class),
            @Arg(column = "fencing_token", javaType = long.class),
            @Arg(column = "last_http_status", javaType = Integer.class),
            @Arg(column = "last_error_code", javaType = String.class),
            @Arg(column = "created_at_ms", javaType = long.class),
            @Arg(column = "updated_at_ms", javaType = long.class)
    })
    SagaStartIntent findStartIntent(@Param("intentId") String intentId);

    /**
     * 业务作用：在领取事务持有行锁时读取 token，不依赖合法正文构造即可隔离损坏记录。
     *
     * @param intentId 本事务刚刚领取的 intent 主键
     * @return 当前持锁 token；没有记录时为 null，不授予其它事务执行资格。
     */
    @Select("SELECT fencing_token FROM saga_start_intent WHERE intent_id = #{intentId}")
    Long findStartFencingToken(@Param("intentId") String intentId);

    /**
     * 业务作用：按本地业务槽位摘要读取候选 intent，用于唯一槽位冲突的完整原文复验。
     *
     * @param businessSlotDigest 完整 32 字节槽位摘要
     * @return 候选 intent，不存在时为 {@code null}
     */
    @Select("""
            SELECT intent_id, tenant_id, workflow, business_key, saga_id, definition_version,
                   expected_definition_digest, trigger_id, deadline_at_ms, traceparent, request_body, request_sha256,
                   business_slot_digest, saga_id_digest, state, attempts, next_attempt_at_ms,
                   lease_owner, lease_until_ms, fencing_token, last_http_status, last_error_code,
                   created_at_ms, updated_at_ms
            FROM saga_start_intent
            WHERE business_slot_digest = #{businessSlotDigest}
            """)
    @ConstructorArgs({
            @Arg(column = "intent_id", javaType = String.class),
            @Arg(column = "tenant_id", javaType = String.class),
            @Arg(column = "workflow", javaType = String.class),
            @Arg(column = "business_key", javaType = String.class),
            @Arg(column = "saga_id", javaType = String.class),
            @Arg(column = "definition_version", javaType = int.class),
            @Arg(column = "expected_definition_digest", javaType = String.class),
            @Arg(column = "trigger_id", javaType = String.class),
            @Arg(column = "deadline_at_ms", javaType = Long.class),
            @Arg(column = "traceparent", javaType = String.class),
            @Arg(column = "request_body", javaType = byte[].class),
            @Arg(column = "request_sha256", javaType = String.class),
            @Arg(column = "business_slot_digest", javaType = byte[].class),
            @Arg(column = "saga_id_digest", javaType = byte[].class),
            @Arg(column = "state", javaType = SagaIntentState.class),
            @Arg(column = "attempts", javaType = int.class),
            @Arg(column = "next_attempt_at_ms", javaType = Long.class),
            @Arg(column = "lease_owner", javaType = String.class),
            @Arg(column = "lease_until_ms", javaType = Long.class),
            @Arg(column = "fencing_token", javaType = long.class),
            @Arg(column = "last_http_status", javaType = Integer.class),
            @Arg(column = "last_error_code", javaType = String.class),
            @Arg(column = "created_at_ms", javaType = long.class),
            @Arg(column = "updated_at_ms", javaType = long.class)
    })
    SagaStartIntent findStartIntentByBusinessSlot(@Param("businessSlotDigest") byte[] businessSlotDigest);

    /**
     * 业务作用：用条件更新领取一个 intent，并递增 fencing token，阻止旧 worker 回写新 worker 的结果。
     *
     * @param intentId     intent 身份
     * @param owner        新 lease owner
     * @param nowMs        当前数据库语义时间
     * @param leaseUntilMs 新 lease 到期时刻
     * @return 成功领取为 1；已被其它 worker 持有或已终态为 0
     */
    @Update("""
            UPDATE saga_start_intent
            SET state = 'IN_FLIGHT', lease_owner = #{owner}, lease_until_ms = #{leaseUntilMs},
                fencing_token = fencing_token + 1, attempts = attempts + 1,
                updated_at_ms = #{nowMs}
            WHERE intent_id = #{intentId}
              AND ((state = 'PENDING' AND (next_attempt_at_ms IS NULL OR next_attempt_at_ms <= #{nowMs}))
                   OR (state = 'IN_FLIGHT' AND lease_until_ms <= #{nowMs}))
            """)
    int claimStartIntent(
            @Param("intentId") String intentId,
            @Param("owner") String owner,
            @Param("nowMs") long nowMs,
            @Param("leaseUntilMs") long leaseUntilMs);

    /**
     * 业务作用：只在持有当前 fencing token 且数据库时钟未到期时写入远端结果，失权 worker 不得覆盖新状态。
     *
     * @param intentId        intent 身份
     * @param owner           当前 lease owner
     * @param fencingToken    当前 fencing token
     * @param state           新状态
     * @param statusCode      最近 HTTP 状态
     * @param errorCode       低基数错误码
     * @param nextAttemptAtMs 下一次尝试时间
     * @param nowMs           更新时间
     * @param dialect         当前事务连接的数据库方言
     * @return 成功更新为 1；失权或状态不匹配为 0
     */
    @UpdateProvider(type = SagaMybatisSqlProvider.class, method = "settleStartIntent")
    int settleStartIntent(
            @Param("intentId") String intentId,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken,
            @Param("state") SagaIntentState state,
            @Param("statusCode") Integer statusCode,
            @Param("errorCode") String errorCode,
            @Param("nextAttemptAtMs") Long nextAttemptAtMs,
            @Param("nowMs") long nowMs,
            @Param("dialect") SagaMybatisDialect dialect);

    /**
     * 业务作用：原子占用共享 HTTP replay claim，与 Rust 的 producer/nonce 主键语义保持一致。
     *
     * @param dialect     数据库方言
     * @param producer    逻辑 producer
     * @param nonce       一次性 nonce
     * @param expiresAtMs claim 到期时刻
     * @return 首次占用为 1，重复 nonce 为 0
     */
    @InsertProvider(type = SagaMybatisSqlProvider.class, method = "claimReplay")
    int claimReplay(
            @Param("dialect") SagaMybatisDialect dialect,
            @Param("producer") String producer,
            @Param("nonce") String nonce,
            @Param("expiresAtMs") long expiresAtMs);

    /**
     * 业务作用：有界清理已过 replay horizon 的 nonce，不影响仍在时间窗内的请求。
     *
     * @param dialect 数据库方言
     * @param nowMs   当前 Unix 毫秒
     * @param limit   单轮最大删除数
     * @return 删除行数
     */
    @DeleteProvider(type = SagaMybatisSqlProvider.class, method = "purgeReplay")
    int purgeReplay(
            @Param("dialect") SagaMybatisDialect dialect,
            @Param("nowMs") long nowMs,
            @Param("limit") int limit);

    /**
     * 业务作用：在业务事实事务中写入唯一 command Inbox，提交后才允许向 Rust 返回 Committed。
     *
     * @param inbox Inbox 记录
     * @return 插入行数；唯一键冲突须由调用方复验原提交身份、结果及业务输入后才能返回 Duplicate
     */
    @Insert("""
            INSERT INTO saga_participant_inbox
                (command_id, effect_id, saga_id, step_name, phase, attempt, status, result_event_id, traceparent, execute_input_digest)
            VALUES
                (#{inbox.commandId}, #{inbox.effectId}, #{inbox.sagaId}, #{inbox.stepName}, #{inbox.phase},
                 #{inbox.attempt}, #{inbox.status}, #{inbox.resultEventId}, #{inbox.traceparent}, #{inbox.executeInputDigest})
            """)
    int insertParticipantInbox(@Param("inbox") SagaParticipantInbox inbox);

    /**
     * 业务作用：锁定已提交 command 的 Inbox 证据，唯一键冲突本身不能作为幂等成功收据。
     *
     * @param commandId 正在复验的稳定命令身份
     * @return 已有 Inbox；不存在时为空，调用方不得返回 Duplicate。
     */
    @Select("""
            SELECT command_id, effect_id, saga_id, step_name, phase, attempt, status, result_event_id, traceparent, execute_input_digest
            FROM saga_participant_inbox WHERE command_id = #{commandId} FOR UPDATE
            """)
    @ConstructorArgs({
            @Arg(column = "command_id", javaType = String.class),
            @Arg(column = "effect_id", javaType = String.class),
            @Arg(column = "saga_id", javaType = String.class),
            @Arg(column = "step_name", javaType = String.class),
            @Arg(column = "phase", javaType = String.class),
            @Arg(column = "attempt", javaType = int.class),
            @Arg(column = "status", javaType = String.class),
            @Arg(column = "result_event_id", javaType = String.class),
            @Arg(column = "traceparent", javaType = String.class),
            @Arg(column = "execute_input_digest", javaType = String.class)
    })
    SagaParticipantInbox findParticipantInboxForUpdate(@Param("commandId") String commandId);

    /**
     * 业务作用：在 execute/cancel 业务写前锁定唯一 participant gate，保持取消屏障与效果提交同事务。
     *
     * @param sagaId   Saga 身份
     * @param stepName step 名称
     * @return 当前 gate，未建立时为 {@code null}
     */
    @Select("""
            SELECT saga_id, step_name, tenant_id, workflow_name, definition_version, definition_digest,
                   forward_status, cancel_status, compensation_status, resolution_status,
                   execute_effect_id, cancel_effect_id, compensate_effect_id, resolve_effect_id,
                   execute_result_status, execute_result_terminal_status, execute_result_reason_code, cancel_result_status, cancel_result_terminal_status, cancel_result_reason_code, compensate_result_status, compensate_result_terminal_status, compensate_result_reason_code, resolve_result_status, resolve_result_terminal_status, resolve_result_reason_code, execute_input_digest
            FROM saga_participant_step
            WHERE saga_id = #{sagaId} AND step_name = #{stepName}
            FOR UPDATE
            """)
    @ConstructorArgs({
            @Arg(column = "saga_id", javaType = String.class),
            @Arg(column = "step_name", javaType = String.class),
            @Arg(column = "tenant_id", javaType = String.class),
            @Arg(column = "workflow_name", javaType = String.class),
            @Arg(column = "definition_version", javaType = int.class),
            @Arg(column = "definition_digest", javaType = String.class),
            @Arg(column = "forward_status", javaType = String.class),
            @Arg(column = "cancel_status", javaType = String.class),
            @Arg(column = "compensation_status", javaType = String.class),
            @Arg(column = "resolution_status", javaType = String.class),
            @Arg(column = "execute_effect_id", javaType = String.class),
            @Arg(column = "cancel_effect_id", javaType = String.class),
            @Arg(column = "compensate_effect_id", javaType = String.class),
            @Arg(column = "resolve_effect_id", javaType = String.class),
            @Arg(column = "execute_result_status", javaType = String.class),
            @Arg(column = "execute_result_terminal_status", javaType = String.class),
            @Arg(column = "execute_result_reason_code", javaType = String.class),
            @Arg(column = "cancel_result_status", javaType = String.class),
            @Arg(column = "cancel_result_terminal_status", javaType = String.class),
            @Arg(column = "cancel_result_reason_code", javaType = String.class),
            @Arg(column = "compensate_result_status", javaType = String.class),
            @Arg(column = "compensate_result_terminal_status", javaType = String.class),
            @Arg(column = "compensate_result_reason_code", javaType = String.class),
            @Arg(column = "resolve_result_status", javaType = String.class),
            @Arg(column = "resolve_result_terminal_status", javaType = String.class),
            @Arg(column = "resolve_result_reason_code", javaType = String.class),
            @Arg(column = "execute_input_digest", javaType = String.class)
    })
    SagaParticipantGate findGateForUpdate(
            @Param("sagaId") String sagaId,
            @Param("stepName") String stepName);

    /**
     * 业务作用：建立 participant gate 的唯一效果锚点，业务层必须随后在同一事务内写事实和结果 Outbox。
     *
     * @param gate gate 初始快照
     * @return 插入行数
     */
    @Insert("""
            INSERT INTO saga_participant_step
                (saga_id, step_name, tenant_id, workflow_name, definition_version, definition_digest,
                 forward_status, cancel_status, compensation_status, resolution_status,
                 execute_effect_id, cancel_effect_id, compensate_effect_id, resolve_effect_id,
                 execute_result_status, execute_result_terminal_status, execute_result_reason_code, cancel_result_status, cancel_result_terminal_status, cancel_result_reason_code, compensate_result_status, compensate_result_terminal_status, compensate_result_reason_code, resolve_result_status, resolve_result_terminal_status, resolve_result_reason_code, execute_input_digest)
            VALUES
                (#{gate.sagaId}, #{gate.stepName}, #{gate.tenantId}, #{gate.workflow}, #{gate.definitionVersion},
                 #{gate.definitionDigest}, #{gate.forwardStatus}, #{gate.cancelStatus},
                 #{gate.compensationStatus}, #{gate.resolutionStatus}, #{gate.executeEffectId},
                 #{gate.cancelEffectId}, #{gate.compensateEffectId}, #{gate.resolveEffectId},
                 #{gate.executeResultStatus}, #{gate.executeResultTerminalStatus}, #{gate.executeResultReasonCode}, #{gate.cancelResultStatus}, #{gate.cancelResultTerminalStatus}, #{gate.cancelResultReasonCode}, #{gate.compensateResultStatus}, #{gate.compensateResultTerminalStatus}, #{gate.compensateResultReasonCode}, #{gate.resolveResultStatus}, #{gate.resolveResultTerminalStatus}, #{gate.resolveResultReasonCode}, #{gate.executeInputDigest})
            """)
    int insertGate(@Param("gate") SagaParticipantGate gate);

    /**
     * 业务作用：在受锁 gate 首次 execute 准入时冻结输入，已有命令证据时禁止以当前请求回填历史绑定。
     *
     * @param sagaId    Saga 身份
     * @param stepName  步骤名称
     * @param commandId 同一事务刚插入的当前 execute 命令
     * @param digest    宿主按固定业务编码计算的 SHA-256 摘要
     * @return 原绑定为空且没有其它 execute 提交记录时更新一行；否则调用方必须回滚。
     */
    @Update("""
            UPDATE saga_participant_step g SET execute_input_digest=#{digest}, updated_at=CURRENT_TIMESTAMP
            WHERE g.saga_id=#{sagaId} AND g.step_name=#{stepName} AND g.execute_input_digest IS NULL
              AND NOT EXISTS (SELECT 1 FROM saga_participant_inbox i
                  WHERE i.effect_id=g.execute_effect_id AND i.phase='execute' AND i.command_id<>#{commandId})
            """)
    int bindExecuteInput(@Param("sagaId") String sagaId, @Param("stepName") String stepName,
                         @Param("commandId") String commandId, @Param("digest") String digest);

    /**
     * 业务作用：按受保护 gate 行更新本地阶段事实，条件状态防止旧 command 覆盖新裁决。
     *
     * @param sagaId         Saga 身份
     * @param stepName       step 名称
     * @param columnName     已由受信业务代码选择的固定状态列名
     * @param nextStatus     新状态
     * @param expectedStatus 预期旧状态
     * @return 成功更新为 1
     */
    @UpdateProvider(type = SagaMybatisSqlProvider.class, method = "updateGateStatus")
    int updateGateStatus(
            @Param("sagaId") String sagaId,
            @Param("stepName") String stepName,
            @Param("columnName") String columnName,
            @Param("nextStatus") String nextStatus,
            @Param("expectedStatus") String expectedStatus);

    /**
     * 业务作用：在调用方持有 gate 行锁的事务内保存完整阶段裁决，与业务事实及结果 Outbox 一起提交。
     *
     * @param sagaId   Saga 身份
     * @param stepName 步骤名称
     * @param phase    已准入的固定阶段
     * @param result   已通过协议校验的裁决
     * @return 匹配 gate 时更新一行；调用方不得在缺少行锁或准入依据时覆盖历史裁决。
     */
    @UpdateProvider(type = SagaMybatisSqlProvider.class, method = "updateGateResult")
    int updateGateResult(@Param("sagaId") String sagaId, @Param("stepName") String stepName,
                         @Param("phase") String phase, @Param("result") SagaStepResult result);

    /**
     * 业务作用：在受锁 gate 上原子绑定 resolve 效果身份与裁决状态，禁止另一效果覆盖当前解决流程。
     *
     * @param sagaId          Saga 身份
     * @param stepName        step 名称
     * @param expectedStatus  受锁快照中的 resolution 状态
     * @param nextStatus      已由事务 wrapper 裁决的下一状态
     * @param resolveEffectId 跨 attempt 稳定的 resolve 效果身份
     * @return 身份和预期状态匹配时更新一行；否则为零，调用方必须回滚
     */
    @Update("""
            UPDATE saga_participant_step
            SET resolution_status = #{nextStatus}, resolve_effect_id = #{resolveEffectId}, updated_at = CURRENT_TIMESTAMP
            WHERE saga_id = #{sagaId} AND step_name = #{stepName}
              AND resolution_status = #{expectedStatus}
              AND (resolve_effect_id IS NULL OR resolve_effect_id = #{resolveEffectId})
            """)
    int updateResolutionGate(
            @Param("sagaId") String sagaId,
            @Param("stepName") String stepName,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("resolveEffectId") String resolveEffectId);

    /**
     * 业务作用：把本地业务事务已生成的 result envelope 放入可重投 Outbox。
     *
     * @param outbox result Outbox 记录
     * @return 插入行数
     */
    @Insert("""
            INSERT INTO saga_result_outbox
                (event_id, saga_id, command_id, payload, traceparent, state, attempts,
                 next_attempt_at_ms, lease_owner, lease_until_ms, fencing_token)
            VALUES
                (#{outbox.eventId}, #{outbox.sagaId}, #{outbox.commandId}, #{outbox.payload},
                 #{outbox.traceparent}, #{outbox.state}, #{outbox.attempts}, #{outbox.nextAttemptAtMs},
                 #{outbox.leaseOwner}, #{outbox.leaseUntilMs}, #{outbox.fencingToken})
            """)
    int insertResultOutbox(@Param("outbox") SagaResultOutbox outbox);

    /**
     * 业务作用：读取冻结的 result envelope 和当前 lease，供 dispatcher 在进程重启后继续原事件投递。
     *
     * @param eventId result event 身份
     * @return 本地记录，不存在时为 {@code null}
     */
    @Select("""
            SELECT event_id, saga_id, command_id, payload, traceparent, state, attempts,
                   next_attempt_at_ms, lease_owner, lease_until_ms, fencing_token
            FROM saga_result_outbox
            WHERE event_id = #{eventId}
            """)
    @ConstructorArgs({
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "saga_id", javaType = String.class),
            @Arg(column = "command_id", javaType = String.class),
            @Arg(column = "payload", javaType = byte[].class),
            @Arg(column = "traceparent", javaType = String.class),
            @Arg(column = "state", javaType = SagaResultOutboxState.class),
            @Arg(column = "attempts", javaType = int.class),
            @Arg(column = "next_attempt_at_ms", javaType = Long.class),
            @Arg(column = "lease_owner", javaType = String.class),
            @Arg(column = "lease_until_ms", javaType = Long.class),
            @Arg(column = "fencing_token", javaType = long.class)
    })
    SagaResultOutbox findResultOutbox(@Param("eventId") String eventId);

    /**
     * 业务作用：在领取更新仍持有行锁时读取动作 token，不依赖损坏正文能够构造业务记录。
     *
     * @param eventId 本事务已经领取的 result 主键
     * @return 当前持锁记录的 token；不存在时为空，调用方应回滚领取。
     */
    @Select("SELECT fencing_token FROM saga_result_outbox WHERE event_id = #{eventId}")
    Long findResultFencingToken(@Param("eventId") String eventId);

    /**
     * 业务作用：领取 result Outbox 并递增 fencing token，防止旧 dispatcher 回写新的投递裁决。
     *
     * @param eventId      result event 身份
     * @param owner        新 lease owner
     * @param nowMs        当前时刻
     * @param leaseUntilMs lease 到期时刻
     * @return 成功领取为 1
     */
    @Update("""
            UPDATE saga_result_outbox
            SET state = 'IN_FLIGHT', lease_owner = #{owner}, lease_until_ms = #{leaseUntilMs},
                fencing_token = fencing_token + 1, attempts = attempts + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE event_id = #{eventId}
              AND ((state = 'PENDING' AND (next_attempt_at_ms IS NULL OR next_attempt_at_ms <= #{nowMs}))
                   OR (state = 'IN_FLIGHT' AND lease_until_ms <= #{nowMs}))
            """)
    int claimResultOutbox(
            @Param("eventId") String eventId,
            @Param("owner") String owner,
            @Param("nowMs") long nowMs,
            @Param("leaseUntilMs") long leaseUntilMs);

    /**
     * 业务作用：仅在 lease/fencing 仍归当前 worker 时确认 Result receipt，网络未知结果保留原行。
     *
     * @param eventId         result event 身份
     * @param owner           当前 lease owner
     * @param fencingToken    当前 fencing token
     * @param state           新状态
     * @param nextAttemptAtMs 下次投递时刻
     * @param dialect         当前事务连接的数据库方言
     * @param errorCode       低敏投递或本地完整性错误码；成功时为空
     * @return 成功更新为 1
     */
    @UpdateProvider(type = SagaMybatisSqlProvider.class, method = "settleResultOutbox")
    int settleResultOutbox(
            @Param("eventId") String eventId,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken,
            @Param("state") SagaResultOutboxState state,
            @Param("nextAttemptAtMs") Long nextAttemptAtMs,
            @Param("dialect") SagaMybatisDialect dialect,
            @Param("errorCode") String errorCode);
}
