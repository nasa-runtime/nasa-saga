package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaEnvelopeCodec;
import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Rust Orchestrator 发给 participant 的 command envelope。
 *
 * @param sagaId              Saga 实例身份
 * @param tenantId            租户身份
 * @param workflow            workflow 名称
 * @param definitionVersion   实例冻结的 definition 版本
 * @param definitionDigest    definition 内容摘要
 * @param step                步骤名称
 * @param phase               execute、cancel、compensate 或 resolve
 * @param attempt             本次投递尝试序号
 * @param effectId            跨 attempt 稳定的业务效果身份
 * @param commandId           本次投递尝试身份
 * @param recoveryOperationId 管理恢复操作身份，可为空
 * @param payload             首次 execute 的 JSON 输入，其余阶段通常为空
 * @param rawPayload          首次 execute 的原始正文对象，其余阶段通常为空；与 payload 互斥
 */
public record SagaCommandEnvelope(
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
        @JsonProperty("recovery_operation_id") @JsonInclude(JsonInclude.Include.NON_NULL)
        String recoveryOperationId,
        @JsonProperty("payload") JsonNode payload,
        @JsonProperty("raw_payload") @JsonInclude(JsonInclude.Include.NON_NULL)
        JsonNode rawPayload) {

    /**
     * 业务作用：把 Rust {@code Option<Value>} 的显式 null 统一为 Java 的缺省正文，保证 raw payload command
     * 在身份复验前不会被误判为携带两种正文来源。
     */
    public SagaCommandEnvelope {
        // Rust 的 Option<Value> 将 wire 层的显式 null 解码为 None；统一归一化后，raw_payload
        // 不会被误判为与空 payload 同时存在的第二种正文来源。
        payload = payload == null || payload.isNull() ? null : payload;
        rawPayload = rawPayload == null || rawPayload.isNull() ? null : rawPayload;
    }

    /**
     * 业务作用：保留旧 Java 调用方构造兼容 JSON command 的能力，并明确不携带 raw payload。
     *
     * @param sagaId              Saga 实例身份
     * @param tenantId            租户身份
     * @param workflow            workflow 名称
     * @param definitionVersion   definition 版本
     * @param definitionDigest    definition 摘要
     * @param step                步骤名称
     * @param phase               阶段名称
     * @param attempt             尝试序号
     * @param effectId            效果身份
     * @param commandId           命令身份
     * @param recoveryOperationId 恢复操作身份
     * @param payload             JSON 输入
     */
    public SagaCommandEnvelope(
            String sagaId,
            String tenantId,
            String workflow,
            int definitionVersion,
            String definitionDigest,
            String step,
            String phase,
            int attempt,
            String effectId,
            String commandId,
            String recoveryOperationId,
            JsonNode payload) {
        this(sagaId, tenantId, workflow, definitionVersion, definitionDigest, step, phase, attempt,
                effectId, commandId, recoveryOperationId, payload, null);
    }

    /**
     * 业务作用：解码并复验 command envelope 的身份派生关系，阻止伪造字段进入本地事务。
     *
     * @param bytes Rust transport 中的 envelope JSON
     * @return 通过身份复验的 command envelope
     */
    public static SagaCommandEnvelope decode(byte[] bytes) {
        SagaCommandEnvelope envelope = SagaEnvelopeCodec.decodeStrict(bytes, SagaCommandEnvelope.class);
        envelope.validate();
        return envelope;
    }

    /**
     * 业务作用：在交给 Java participant handler 前复验 Rust 的稳定身份和 phase 合同。
     *
     * @throws SagaProtocolException 身份或字段不符合合同
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
                || !SagaIds.commandId(effectId, attempt).equals(commandId)) {
            throw new SagaProtocolException("command envelope identity does not match derived ids");
        }
        if (recoveryOperationId != null) {
            SagaIds.requireOpaque(recoveryOperationId, "recovery_operation_id", 190);
            if (!"compensate".equals(phase) && !"resolve".equals(phase)) {
                throw new SagaProtocolException("recovery_operation_id is invalid for this phase");
            }
        }
        if (payload != null && rawPayload != null) {
            throw new SagaProtocolException("command envelope contains two payload forms");
        }
        if (rawPayload != null) {
            validateRawPayload(rawPayload);
        }
    }

    /**
     * 业务作用：把 envelope 的兼容 JSON 或原始正文投影为 Rust `SagaPayload`，供业务只读取冻结字节。
     *
     * @return command 携带正文时返回原始合同；其余阶段没有正文时返回 {@code null}
     */
    public SagaPayload businessPayload() {
        validate();
        if (rawPayload != null) {
            return rawPayloadValue(rawPayload);
        }
        return payload == null
                ? null
                : new SagaPayload("application/json", "", SagaEnvelopeCodec.encode(payload));
    }

    /**
     * 业务作用：按 `@Saga` 声明的不可变正文合同复验 command，避免 participant 用错误 schema 解读字节。
     *
     * @param contentType handler 声明的规范媒体类型
     * @param schemaId    handler 声明的稳定 schema 身份
     */
    public void verifyPayloadContract(String contentType, String schemaId) {
        SagaCapabilityPayloadContract expected = new SagaCapabilityPayloadContract(contentType, schemaId);
        SagaPayload actual = businessPayload();
        if (actual != null && (!actual.contentType().equals(expected.contentType())
                || !actual.schemaId().equals(expected.schemaId()))) {
            throw new SagaProtocolException("command payload contract does not match the step");
        }
    }

    /**
     * 业务作用：将已验证 command 编码为 transport envelope，确保 producer 不提交非法身份。
     *
     * @return UTF-8 JSON 字节
     */
    public byte[] encode() {
        validate();
        return SagaEnvelopeCodec.encode(this);
    }

    /**
     * 业务作用：校验 Rust `SagaPayload` 的 JSON 投影，确保原始字节不会被误读成另一种正文合同。
     *
     * @param payload raw_payload JSON 对象
     */
    private static void validateRawPayload(JsonNode payload) {
        if (payload.size() != 3) {
            throw new SagaProtocolException("raw_payload contains an unknown field");
        }
        JsonNode contentType = payload.get("content_type");
        JsonNode schemaId = payload.get("schema_id");
        JsonNode body = payload.get("body");
        if (contentType == null || !contentType.isTextual() || contentType.textValue().isBlank()
                || schemaId == null || !schemaId.isTextual() || body == null || !body.isArray()
                || SagaIds.utf8Length(contentType.textValue()) > 128
                || !validContentType(contentType.textValue())
                || SagaIds.utf8Length(schemaId.textValue()) > 256
                || !schemaId.textValue().equals(schemaId.textValue().strip())
                || schemaId.textValue().chars().anyMatch(Character::isISOControl)
                || (!"application/json".equals(contentType.textValue()) && schemaId.textValue().isEmpty())) {
            throw new SagaProtocolException("raw_payload does not match SagaPayload");
        }
        byte[] rawBody = new byte[body.size()];
        int index = 0;
        for (JsonNode value : body) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()
                    || value.intValue() < 0 || value.intValue() > 255) {
                throw new SagaProtocolException("raw_payload body is not a byte array");
            }
            rawBody[index++] = (byte) value.intValue();
        }
        if ("application/json".equals(contentType.textValue())) {
            try {
                SagaEnvelopeCodec.decodeJsonStrict(rawBody);
            } catch (SagaProtocolException exception) {
                throw new SagaProtocolException("raw_payload JSON body is invalid", exception);
            }
        }
    }

    /**
     * 业务作用：把已校验的 raw_payload byte array 还原为不可变正文，确保业务拿到的是 Rust 投影的原始字节。
     *
     * @param payload raw_payload JSON 对象
     * @return 原始正文合同
     */
    private static SagaPayload rawPayloadValue(JsonNode payload) {
        validateRawPayload(payload);
        JsonNode contentType = payload.get("content_type");
        JsonNode schemaId = payload.get("schema_id");
        JsonNode body = payload.get("body");
        byte[] rawBody = new byte[body.size()];
        int index = 0;
        for (JsonNode value : body) {
            rawBody[index++] = (byte) value.intValue();
        }
        return new SagaPayload(contentType.textValue(), schemaId.textValue(), rawBody);
    }

    /**
     * 业务作用：执行 envelope raw payload 的 media type 字符集校验，保持与 Rust payload 合同一致。
     *
     * @param value 待校验媒体类型
     * @return 合法时返回真
     */
    private static boolean validContentType(String value) {
        int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf('/', separator + 1) >= 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (index == separator) {
                continue;
            }
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && "!#$&^_.+-".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }
}
