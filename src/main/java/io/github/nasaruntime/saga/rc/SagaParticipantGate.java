package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

/**
 * Java participant step gate 的持久快照。
 *
 * @param sagaId                         Saga 身份
 * @param stepName                       step 名称
 * @param tenantId                       租户身份
 * @param workflow                       workflow 名称
 * @param definitionVersion              definition 版本
 * @param definitionDigest               definition 摘要
 * @param forwardStatus                  execute 状态
 * @param cancelStatus                   cancel 状态
 * @param compensationStatus             compensate 状态
 * @param resolutionStatus               resolve 状态
 * @param executeEffectId                execute effect 身份
 * @param cancelEffectId                 cancel effect 身份，可为空
 * @param compensateEffectId             compensate effect 身份，可为空
 * @param resolveEffectId                resolve effect 身份，可为空
 * @param executeResultStatus            execute 已提交裁决的状态，尚无裁决时为空
 * @param executeResultTerminalStatus    execute 已提交裁决的正向终态，尚无裁决时为空
 * @param executeResultReasonCode        execute 已提交裁决的稳定原因码，尚无裁决时为空
 * @param cancelResultStatus             cancel 已提交裁决的状态，尚无裁决时为空
 * @param cancelResultTerminalStatus     cancel 已提交裁决的正向终态，尚无裁决时为空
 * @param cancelResultReasonCode         cancel 已提交裁决的稳定原因码，尚无裁决时为空
 * @param compensateResultStatus         compensate 已提交裁决的状态，尚无裁决时为空
 * @param compensateResultTerminalStatus compensate 已提交裁决的正向终态，尚无裁决时为空
 * @param compensateResultReasonCode     compensate 已提交裁决的稳定原因码，尚无裁决时为空
 * @param resolveResultStatus            resolve 已提交裁决的状态，尚无裁决时为空
 * @param resolveResultTerminalStatus    resolve 已提交裁决的正向终态，尚无裁决时为空
 * @param resolveResultReasonCode        resolve 已提交裁决的稳定原因码，尚无裁决时为空
 * @param executeInputDigest             首次 execute 准入时冻结的业务输入摘要，未准入或宿主不使用绑定时为空
 */
public record SagaParticipantGate(
        String sagaId,
        String stepName,
        String tenantId,
        String workflow,
        int definitionVersion,
        String definitionDigest,
        String forwardStatus,
        String cancelStatus,
        String compensationStatus,
        String resolutionStatus,
        String executeEffectId,
        String cancelEffectId,
        String compensateEffectId,
        String resolveEffectId,
        String executeResultStatus,
        String executeResultTerminalStatus,
        String executeResultReasonCode,
        String cancelResultStatus,
        String cancelResultTerminalStatus,
        String cancelResultReasonCode,
        String compensateResultStatus,
        String compensateResultTerminalStatus,
        String compensateResultReasonCode,
        String resolveResultStatus,
        String resolveResultTerminalStatus,
        String resolveResultReasonCode,
        String executeInputDigest) {

    /**
     * 业务作用：核对 gate 合同、阶段结果与可选首次输入摘要，确保后续 command 只能在同一 Saga/step/definition 上竞争。
     * 参数说明：各记录分量的业务含义见类型参数说明。
     * 返回：身份、裁决和摘要格式合法的快照；格式不符时抛出协议异常，业务一致性由宿主另行核对。
     */
    public SagaParticipantGate {
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        SagaIds.requireStructured(stepName, "step");
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        SagaIds.requireStructured(workflow, "workflow");
        SagaIds.requirePositive(definitionVersion, "definition_version");
        SagaIds.requireDigest(definitionDigest);
        requireStatus(forwardStatus, "forward_status");
        requireStatus(cancelStatus, "cancel_status");
        requireStatus(compensationStatus, "compensation_status");
        requireStatus(resolutionStatus, "resolution_status");
        requireEffectId(executeEffectId, SagaIds.effectId(sagaId, definitionVersion, stepName, "execute"),
                "execute_effect_id");
        requireOptionalEffectId(cancelEffectId,
                SagaIds.effectId(sagaId, definitionVersion, stepName, "cancel"), "cancel_effect_id");
        requireOptionalEffectId(compensateEffectId,
                SagaIds.effectId(sagaId, definitionVersion, stepName, "compensate"), "compensate_effect_id");
        requireOptionalEffectId(resolveEffectId,
                SagaIds.effectId(sagaId, definitionVersion, stepName, "resolve"), "resolve_effect_id");
        decision(executeResultStatus, executeResultTerminalStatus, executeResultReasonCode);
        decision(cancelResultStatus, cancelResultTerminalStatus, cancelResultReasonCode);
        decision(compensateResultStatus, compensateResultTerminalStatus, compensateResultReasonCode);
        decision(resolveResultStatus, resolveResultTerminalStatus, resolveResultReasonCode);
        if (executeInputDigest != null) SagaIds.requireDigest(executeInputDigest);
    }

    /**
     * 业务作用：构造不含首次输入绑定的阶段快照，要求输入绑定的宿主须另行核对已提交证据。
     *
     * @param sagaId                         Saga 身份
     * @param stepName                       step 名称
     * @param tenantId                       租户身份
     * @param workflow                       workflow 名称
     * @param definitionVersion              definition 版本
     * @param definitionDigest               definition 摘要
     * @param forwardStatus                  execute 状态
     * @param cancelStatus                   cancel 状态
     * @param compensationStatus             compensate 状态
     * @param resolutionStatus               resolve 状态
     * @param executeEffectId                execute effect 身份
     * @param cancelEffectId                 cancel effect 身份，可为空
     * @param compensateEffectId             compensate effect 身份，可为空
     * @param resolveEffectId                resolve effect 身份，可为空
     * @param executeResultStatus            execute 已提交裁决的状态，尚无裁决时为空
     * @param executeResultTerminalStatus    execute 已提交裁决的正向终态，尚无裁决时为空
     * @param executeResultReasonCode        execute 已提交裁决的稳定原因码，尚无裁决时为空
     * @param cancelResultStatus             cancel 已提交裁决的状态，尚无裁决时为空
     * @param cancelResultTerminalStatus     cancel 已提交裁决的正向终态，尚无裁决时为空
     * @param cancelResultReasonCode         cancel 已提交裁决的稳定原因码，尚无裁决时为空
     * @param compensateResultStatus         compensate 已提交裁决的状态，尚无裁决时为空
     * @param compensateResultTerminalStatus compensate 已提交裁决的正向终态，尚无裁决时为空
     * @param compensateResultReasonCode     compensate 已提交裁决的稳定原因码，尚无裁决时为空
     * @param resolveResultStatus            resolve 已提交裁决的状态，尚无裁决时为空
     * @param resolveResultTerminalStatus    resolve 已提交裁决的正向终态，尚无裁决时为空
     * @param resolveResultReasonCode        resolve 已提交裁决的稳定原因码，尚无裁决时为空
     *                                       返回：阶段裁决快照；空输入摘要不证明存量 execute 的原业务输入。
     */
    public SagaParticipantGate(
            String sagaId,
            String stepName,
            String tenantId,
            String workflow,
            int definitionVersion,
            String definitionDigest,
            String forwardStatus,
            String cancelStatus,
            String compensationStatus,
            String resolutionStatus,
            String executeEffectId,
            String cancelEffectId,
            String compensateEffectId,
            String resolveEffectId,
            String executeResultStatus,
            String executeResultTerminalStatus,
            String executeResultReasonCode,
            String cancelResultStatus,
            String cancelResultTerminalStatus,
            String cancelResultReasonCode,
            String compensateResultStatus,
            String compensateResultTerminalStatus,
            String compensateResultReasonCode,
            String resolveResultStatus,
            String resolveResultTerminalStatus,
            String resolveResultReasonCode) {
        this(sagaId, stepName, tenantId, workflow, definitionVersion, definitionDigest,
                forwardStatus, cancelStatus, compensationStatus, resolutionStatus,
                executeEffectId, cancelEffectId, compensateEffectId, resolveEffectId,
                executeResultStatus, executeResultTerminalStatus, executeResultReasonCode,
                cancelResultStatus, cancelResultTerminalStatus, cancelResultReasonCode,
                compensateResultStatus, compensateResultTerminalStatus, compensateResultReasonCode,
                resolveResultStatus, resolveResultTerminalStatus, resolveResultReasonCode, null);
    }


    /**
     * 业务作用：建立尚未产生阶段裁决的 gate；调用方须在同一事务中补齐已结束阶段的结果。
     *
     * @param sagaId             Saga 身份
     * @param stepName           步骤名称
     * @param tenantId           租户身份
     * @param workflow           工作流名称
     * @param definitionVersion  定义版本
     * @param definitionDigest   定义摘要
     * @param forwardStatus      正向状态
     * @param cancelStatus       取消状态
     * @param compensationStatus 补偿状态
     * @param resolutionStatus   解决状态
     * @param executeEffectId    正向效果身份
     * @param cancelEffectId     可选取消效果身份
     * @param compensateEffectId 可选补偿效果身份
     * @param resolveEffectId    可选解决效果身份
     *                           返回：尚无结果证据的初始快照，不能据此推断已提交原因。
     */
    public SagaParticipantGate(String sagaId, String stepName, String tenantId, String workflow,
                               int definitionVersion, String definitionDigest, String forwardStatus, String cancelStatus,
                               String compensationStatus, String resolutionStatus, String executeEffectId,
                               String cancelEffectId, String compensateEffectId, String resolveEffectId) {
        this(sagaId, stepName, tenantId, workflow, definitionVersion, definitionDigest, forwardStatus,
                cancelStatus, compensationStatus, resolutionStatus, executeEffectId, cancelEffectId,
                compensateEffectId, resolveEffectId, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    /**
     * 业务作用：读取某阶段原样持久化的业务裁决，不按 gate 控制状态推测原因。
     *
     * @param phase execute、cancel、compensate 或 resolve
     * @return 已提交的完整裁决；尚无证据为 null，非法阶段拒绝。
     */
    public SagaStepResult result(String phase) {
        return switch (phase) {
            case "execute" -> decision(executeResultStatus, executeResultTerminalStatus, executeResultReasonCode);
            case "cancel" -> decision(cancelResultStatus, cancelResultTerminalStatus, cancelResultReasonCode);
            case "compensate" ->
                    decision(compensateResultStatus, compensateResultTerminalStatus, compensateResultReasonCode);
            case "resolve" -> decision(resolveResultStatus, resolveResultTerminalStatus, resolveResultReasonCode);
            default -> throw new SagaProtocolException("unsupported participant result phase");
        };
    }

    /**
     * 业务作用：防止缺失状态的部分裁决或非法原因进入重放事实。
     *
     * @param status   已提交状态，可为空
     * @param terminal 正向终态，可为空
     * @param reason   稳定原因，可为空
     * @return 完整合法结果或完全空的未裁决标记；部分记录拒绝。
     */
    private static SagaStepResult decision(String status, String terminal, String reason) {
        if (status == null) {
            if (terminal != null || reason != null) throw new SagaProtocolException("participant result is incomplete");
            return null;
        }
        return new SagaStepResult(status, terminal, reason);
    }

    /**
     * 业务作用：限制持久状态的容量与控制字符，拒绝不完整的 gate。
     *
     * @param value 状态值
     * @param field 字段名
     *              返回：合法时通过，否则拒绝构造。
     */
    private static void requireStatus(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 32
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new SagaProtocolException(field + " is not a bounded gate status");
        }
    }

    /**
     * 业务作用：复验稳定效果与 gate 身份的派生关系。
     *
     * @param value    待核对效果
     * @param expected 确定派生的效果
     * @param field    字段名
     *                 返回：相符时通过，否则拒绝构造。
     */
    private static void requireEffectId(String value, String expected, String field) {
        SagaIds.requireUuid(value, field);
        if (!expected.equals(value)) {
            throw new SagaProtocolException(field + " does not match the gate identity");
        }
    }

    /**
     * 业务作用：对已建立的可选阶段核对效果身份。
     *
     * @param value    可选效果
     * @param expected 确定派生的效果
     * @param field    字段名
     *                 返回：空值或正确身份通过，否则拒绝构造。
     */
    private static void requireOptionalEffectId(String value, String expected, String field) {
        if (value != null) {
            requireEffectId(value, expected, field);
        }
    }
}
