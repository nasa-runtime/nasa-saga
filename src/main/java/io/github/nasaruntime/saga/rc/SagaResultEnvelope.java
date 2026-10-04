package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaEnvelopeCodec;
import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Java participant 发回 Rust Orchestrator 的 result envelope。
 *
 * @param eventId           由 commandId 确定性派生的结果事件身份
 * @param sagaId            Saga 实例身份
 * @param tenantId          租户身份
 * @param workflow          workflow 名称
 * @param definitionVersion definition 版本
 * @param definitionDigest  definition 内容摘要
 * @param step              步骤名称
 * @param phase             执行阶段
 * @param attempt           投递尝试序号
 * @param effectId          业务效果身份
 * @param commandId         command 身份
 * @param status            Rust attempt journal 的稳定终态名称
 * @param terminalStatus    仅 ALREADY_TERMINAL 使用的正向真实终态
 * @param reasonCode        稳定原因码，可为空
 */
public record SagaResultEnvelope(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("saga_id") String sagaId,
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("workflow") String workflow,
        @JsonProperty("definition_version") int definitionVersion,
        @JsonProperty("definition_digest") String definitionDigest,
        @JsonProperty("step") String step,
        @JsonProperty("phase") String phase,
        @JsonProperty("attempt") int attempt,
        @JsonProperty("effect_id") String effectId,
        @JsonProperty("command_id") String commandId,
        @JsonProperty("status") String status,
        @JsonProperty("terminal_status") String terminalStatus,
        @JsonProperty("reason_code") String reasonCode) {

    /**
     * 业务作用：从已验证 command 构造结果身份，避免业务自行生成 event_id 或复制身份字段。
     *
     * @param command        已验证的 command envelope
     * @param status         当前 phase 的结果状态
     * @param terminalStatus 取消已发现正向终态时的真实状态，可为空
     * @param reasonCode     稳定原因码，可为空
     * @return 身份与 command 一致的 result envelope
     */
    public static SagaResultEnvelope forCommand(
            SagaCommandEnvelope command,
            String status,
            String terminalStatus,
            String reasonCode) {
        command.validate();
        return new SagaResultEnvelope(
                SagaIds.resultEventId(command.commandId()),
                command.sagaId(),
                command.tenantId(),
                command.workflow(),
                command.definitionVersion(),
                command.definitionDigest(),
                command.step(),
                command.phase(),
                command.attempt(),
                command.effectId(),
                command.commandId(),
                status,
                terminalStatus,
                reasonCode);
    }

    /**
     * 业务作用：解码并复验 result envelope，防止结果伪造、跨 phase 状态和重复事件身份进入 Orchestrator。
     *
     * @param bytes Rust transport 中的 result JSON
     * @return 通过合同复验的 result envelope
     */
    public static SagaResultEnvelope decode(byte[] bytes) {
        SagaResultEnvelope envelope = SagaEnvelopeCodec.decodeStrict(bytes, SagaResultEnvelope.class);
        envelope.validate();
        return envelope;
    }

    /**
     * 业务作用：验证结果状态与 phase、event_id 和 terminal_status 的组合符合 Rust 封闭合同。
     *
     * @throws SagaProtocolException 不符合合同
     */
    public void validate() {
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        SagaIds.requireStructured(workflow, "workflow");
        SagaIds.requirePositive(definitionVersion, "definition_version");
        SagaIds.requireDigest(definitionDigest);
        SagaIds.requireStructured(step, "step");
        SagaIds.requirePhase(phase);
        SagaIds.requirePositive(attempt, "attempt");
        if (!SagaIds.effectId(sagaId, definitionVersion, step, phase).equals(effectId)
                || !SagaIds.commandId(effectId, attempt).equals(commandId)
                || !SagaIds.resultEventId(commandId).equals(eventId)) {
            throw new SagaProtocolException("result envelope identity does not match derived ids");
        }
        if (!validStatusForPhase(phase, status)) {
            throw new SagaProtocolException("result status is invalid for its phase");
        }
        if ("ALREADY_TERMINAL".equals(status)) {
            if (!"SUCCEEDED".equals(terminalStatus) && !"REJECTED".equals(terminalStatus)
                    && !"HALTED".equals(terminalStatus)) {
                throw new SagaProtocolException("ALREADY_TERMINAL requires a forward terminal status");
            }
        } else if (terminalStatus != null) {
            throw new SagaProtocolException("terminal_status is only valid for ALREADY_TERMINAL");
        }
        if (reasonCode != null) {
            SagaIds.requireReasonCode(reasonCode);
        }
    }

    /**
     * 业务作用：把已验证 result 编码为 Rust result transport 使用的 UTF-8 JSON。
     *
     * @return UTF-8 JSON 字节
     */
    public byte[] encode() {
        validate();
        return SagaEnvelopeCodec.encode(this);
    }

    /**
     * 业务作用：限制每个 phase 能向 Orchestrator 证明的结果状态，防止跨阶段状态推进。
     *
     * @param phase  command 阶段
     * @param status participant 结果状态
     * @return 状态组合属于 Rust 封闭合同则为 {@code true}
     */
    private static boolean validStatusForPhase(String phase, String status) {
        return switch (phase) {
            case "execute", "resolve" -> "SUCCEEDED".equals(status)
                    || "REJECTED".equals(status)
                    || "UNKNOWN".equals(status)
                    || "HALTED".equals(status);
            case "cancel" -> "CANCEL_CONFIRMED".equals(status)
                    || "ALREADY_TERMINAL".equals(status)
                    || "RESOLUTION_PENDING".equals(status);
            case "compensate" -> "SUCCEEDED".equals(status)
                    || "UNKNOWN".equals(status)
                    || "HALTED".equals(status);
            default -> false;
        };
    }
}
