package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

/**
 * Java participant command Inbox 的持久记录。
 *
 * @param commandId          command 去重身份
 * @param effectId           跨 attempt 的业务效果身份
 * @param sagaId             Saga 身份
 * @param stepName           step 名称
 * @param phase              command phase
 * @param attempt            command attempt
 * @param status             本地处理状态
 * @param resultEventId      已生成的 result event 身份，可为空
 * @param traceparent        固定传播的链路上下文，可为空
 * @param executeInputDigest 当前 execute 命令的业务输入摘要；由宿主定义编码，不使用输入绑定的宿主或其它阶段为空
 */
public record SagaParticipantInbox(
        String commandId,
        String effectId,
        String sagaId,
        String stepName,
        String phase,
        int attempt,
        String status,
        String resultEventId,
        String traceparent,
        String executeInputDigest) {

    /**
     * 业务作用：校验 command 去重身份、结果关联及可选 execute 输入摘要，阻止其它阶段混入正向输入证据。
     * 参数说明：各记录分量的业务含义见类型参数说明。
     * 返回：身份和摘要格式合法的记录；非法阶段、格式或结果关联抛出协议异常。
     */
    public SagaParticipantInbox {
        SagaIds.requireUuid(commandId, "command_id");
        SagaIds.requireUuid(effectId, "effect_id");
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        SagaIds.requireStructured(stepName, "step");
        SagaIds.requirePhase(phase);
        SagaIds.requirePositive(attempt, "attempt");
        if (status == null || status.isBlank() || status.length() > 32) {
            throw new SagaProtocolException("inbox status is not bounded");
        }
        if (resultEventId != null) {
            SagaIds.requireUuid(resultEventId, "result_event_id");
            if (!SagaIds.resultEventId(commandId).equals(resultEventId)) {
                throw new SagaProtocolException("result_event_id does not match command_id");
            }
        }
        if (traceparent != null && SagaIds.utf8Length(traceparent) > 55) {
            throw new SagaProtocolException("traceparent is too long");
        }
        if (executeInputDigest != null) {
            SagaIds.requireDigest(executeInputDigest);
            if (!"execute".equals(phase)) throw new SagaProtocolException("input digest requires execute phase");
        }
    }

    /**
     * 业务作用：为不使用 execute 输入绑定的宿主构造命令记录，不从效果身份推导业务输入。
     *
     * @param commandId     命令身份
     * @param effectId      稳定效果身份
     * @param sagaId        Saga 身份
     * @param stepName      步骤名称
     * @param phase         命令阶段
     * @param attempt       尝试序号
     * @param status        本地提交状态
     * @param resultEventId 原结果事件身份
     * @param traceparent   可选链路上下文
     *                      返回：不携带输入摘要的记录；要求绑定输入的宿主不得以此构造 execute 提交证据。
     */
    public SagaParticipantInbox(String commandId, String effectId, String sagaId, String stepName, String phase,
                                int attempt, String status, String resultEventId, String traceparent) {
        this(commandId, effectId, sagaId, stepName, phase, attempt, status, resultEventId, traceparent, null);
    }
}
