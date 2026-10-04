package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;

/**
 * 交给 `@Saga` 业务 service 的只读执行上下文，对应 Rust `SagaContext`。
 *
 * @param sagaId            Saga 实例身份
 * @param tenantId          租户身份
 * @param workflow          workflow 名称
 * @param definitionVersion 实例冻结的 definition 版本
 * @param definitionDigest  definition 摘要
 * @param step              step 名称
 * @param phase             当前 command 阶段
 * @param attempt           当前投递尝试
 * @param effectId          当前操作的稳定效果身份
 * @param targetPhase       当前操作正在裁决的原始阶段
 * @param targetEffectId    当前操作正在裁决的原始效果身份
 * @param commandId         当前投递尝试身份
 * @param payload           当前 command 正文，可为空
 */
public record SagaContext(
        String sagaId,
        String tenantId,
        String workflow,
        int definitionVersion,
        String definitionDigest,
        String step,
        String phase,
        int attempt,
        String effectId,
        String targetPhase,
        String targetEffectId,
        String commandId,
        SagaPayload payload) {

    /**
     * 业务作用：冻结 Rust 身份派生所需的全部上下文，阻止业务 service 自行替换跨尝试效果身份。
     */
    public SagaContext {
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        SagaIds.requireStructured(workflow, "workflow");
        SagaIds.requirePositive(definitionVersion, "definition_version");
        SagaIds.requireDigest(definitionDigest);
        SagaIds.requireStructured(step, "step");
        SagaIds.requirePhase(phase);
        SagaIds.requirePositive(attempt, "attempt");
        SagaIds.requireUuid(effectId, "effect_id");
        SagaIds.requirePhase(targetPhase);
        SagaIds.requireUuid(targetEffectId, "target_effect_id");
        SagaIds.requireUuid(commandId, "command_id");
    }

    /**
     * 业务作用：由已经通过 envelope 身份校验的 command 构造上下文，并按当前 phase 派生默认目标效果。
     * resolve/cancel 的事务 wrapper 必须在 gate 裁决目标后调用 withTargetPhase，默认值不代表原业务效果。
     *
     * @param command 已验证的 command envelope
     * @param payload 已按注解合同复验的业务正文
     * @return 与 Rust `SagaContext::new` 同源的上下文
     */
    public static SagaContext fromCommand(SagaCommandEnvelope command, SagaPayload payload) {
        command.validate();
        return new SagaContext(
                command.sagaId(),
                command.tenantId(),
                command.workflow(),
                command.definitionVersion(),
                command.definitionDigest(),
                command.step(),
                command.phase(),
                command.attempt(),
                command.effectId(),
                command.phase(),
                command.effectId(),
                command.commandId(),
                payload);
    }

    /**
     * 业务作用：把 cancel/resolve 的业务上下文绑定到 gate 已裁决的原始效果，避免查询操作误用自身 effect。
     *
     * @param targetPhase 正在被 cancel/resolve 裁决的原始阶段
     * @return 保留当前操作身份、但带有新目标效果身份的上下文
     */
    public SagaContext withTargetPhase(String targetPhase) {
        SagaIds.requirePhase(targetPhase);
        return new SagaContext(
                sagaId,
                tenantId,
                workflow,
                definitionVersion,
                definitionDigest,
                step,
                phase,
                attempt,
                effectId,
                targetPhase,
                SagaIds.effectId(sagaId, definitionVersion, step, targetPhase),
                commandId,
                payload);
    }

    /**
     * 业务作用：读取跨 attempt 稳定的业务效果身份，供本地唯一键和外部幂等键使用。
     *
     * @return effect 身份
     */
    public String targetEffectId() {
        return targetEffectId;
    }
}
