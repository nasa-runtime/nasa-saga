package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Java client 发往 Rust Orchestrator 的 Saga start 请求。
 *
 * @param tenantId                 租户身份
 * @param sagaId                   调用方生成并在重试中保持不变的 Saga 身份
 * @param workflow                 已激活的 workflow 名称
 * @param definitionVersion        调用方选择的 definition 版本
 * @param expectedDefinitionDigest 可选的定义摘要门禁
 * @param businessKey              业务幂等键
 * @param triggerId                本次发起意图身份，重试时保持不变
 * @param input                    首个 execute 的 JSON 输入，可为空
 * @param payload                  首个 execute 的原始正文，可为空；与 input 互斥
 * @param deadline                 Saga 实例业务期限，可为空
 * @param traceparent              可选的 W3C traceparent
 */
public record SagaStartRequest(
        String tenantId,
        String sagaId,
        String workflow,
        int definitionVersion,
        String expectedDefinitionDigest,
        String businessKey,
        String triggerId,
        JsonNode input,
        SagaPayload payload,
        Instant deadline,
        String traceparent) {

    /**
     * 业务作用：保留既有 JSON start 调用方的构造入口，并明确不携带原始正文。
     *
     * @param tenantId                 租户身份
     * @param sagaId                   Saga 身份
     * @param workflow                 workflow 名称
     * @param definitionVersion        definition 版本
     * @param expectedDefinitionDigest 可选的 definition 摘要
     * @param businessKey              业务幂等键
     * @param triggerId                发起意图身份
     * @param input                    JSON 输入
     * @param deadline                 Saga 期限
     * @param traceparent              W3C traceparent
     */
    public SagaStartRequest(
            String tenantId,
            String sagaId,
            String workflow,
            int definitionVersion,
            String expectedDefinitionDigest,
            String businessKey,
            String triggerId,
            JsonNode input,
            Instant deadline,
            String traceparent) {
        this(tenantId, sagaId, workflow, definitionVersion, expectedDefinitionDigest, businessKey,
                triggerId, input, null, deadline, traceparent);
    }

    /**
     * 业务作用：在发起远程 RPC 前校验稳定身份和摘要门禁，避免重试时产生不同请求意图。
     *
     * @throws SagaProtocolException 请求不符合边界
     */
    public void validate() {
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        SagaIds.requireStructured(workflow, "workflow");
        SagaIds.requirePositive(definitionVersion, "definition_version");
        if (expectedDefinitionDigest != null && !expectedDefinitionDigest.isEmpty()) {
            SagaIds.requireDigest(expectedDefinitionDigest);
        }
        SagaIds.requireOpaque(businessKey, "business_key", 256);
        SagaIds.requireOpaque(triggerId, "trigger_id", 190);
        if (input != null && payload != null) {
            throw new SagaProtocolException("Saga start input and payload are mutually exclusive");
        }
        if (deadline != null) {
            try {
                deadline.toEpochMilli();
            } catch (ArithmeticException exception) {
                throw new SagaProtocolException("deadline is outside Rust epoch-millisecond range", exception);
            }
        }
    }
}
