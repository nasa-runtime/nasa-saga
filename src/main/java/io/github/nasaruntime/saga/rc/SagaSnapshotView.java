package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;
import io.github.nasaruntime.saga.SagaTraceparent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Rust control API 返回的实例快照，不携带业务 payload。
 *
 * @param tenantId          租户身份
 * @param sagaId            Saga 实例身份
 * @param workflow          workflow 名称
 * @param definitionVersion 冻结的 definition 版本
 * @param definitionDigest  冻结的 definition 摘要
 * @param businessKey       Rust 去重槽位中的业务键
 * @param status            Saga 全局业务状态
 * @param controlState      管理控制状态
 * @param direction         FORWARD 或 COMPENSATING
 * @param currentStep       当前步骤，可为空
 * @param stateVersion      非负业务状态版本
 * @param controlVersion    非负控制状态版本
 * @param deadlineAtMs      业务期限，可为空
 * @param failureCode       脱敏失败原因码，可为空
 * @param traceparent       创建时的链路上下文，可为空
 * @param createdAtMs       查询结果必需的创建时间，start/get 可为空
 * @param updatedAtMs       查询结果必需的更新时间，start/get 可为空
 */
public record SagaSnapshotView(
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("saga_id") String sagaId,
        @JsonProperty("workflow") String workflow,
        @JsonProperty("definition_version") int definitionVersion,
        @JsonProperty("definition_digest") String definitionDigest,
        @JsonProperty("business_key") String businessKey,
        @JsonProperty("status") String status,
        @JsonProperty("control_state") String controlState,
        @JsonProperty("direction") String direction,
        @JsonProperty("current_step") String currentStep,
        @JsonProperty("state_version") long stateVersion,
        @JsonProperty("control_version") long controlVersion,
        @JsonProperty("deadline_at_ms") Long deadlineAtMs,
        @JsonProperty("failure_code") String failureCode,
        @JsonProperty("traceparent") String traceparent,
        @JsonProperty("created_at_ms") Long createdAtMs,
        @JsonProperty("updated_at_ms") Long updatedAtMs) {

    private static final Set<String> STATUSES = Set.of("RUNNING", "CANCELLING", "WAITING_RESOLUTION",
            "COMPENSATING", "COMPLETED", "COMPENSATED", "MANUAL_INTERVENTION", "MANUALLY_CLOSED");
    private static final Set<String> CONTROL_STATES = Set.of("ACTIVE", "PAUSED");
    private static final Set<String> DIRECTIONS = Set.of("FORWARD", "COMPENSATING");

    /**
     * 业务作用：确认 Rust 快照包含完整身份、封闭状态值和 Java 可表示的非负版本。
     * 参数说明：显式参数对应记录字段；可空字段按各读取 API 的投影合同解释。
     * 返回：完整快照保留原值；身份、状态、步骤或版本非法时抛出协议异常。
     */
    public SagaSnapshotView {
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        SagaIds.requireStructured(workflow, "workflow");
        SagaIds.requirePositive(definitionVersion, "definition_version");
        SagaIds.requireDigest(definitionDigest);
        SagaIds.requireOpaque(businessKey, "business_key", 256);
        // 状态决定业务推进语义；未知文本不能作为权威事实交付，新增状态须同步协议合同。
        if (status == null || !STATUSES.contains(status)
                || controlState == null || !CONTROL_STATES.contains(controlState)
                || direction == null || !DIRECTIONS.contains(direction)) {
            throw new SagaProtocolException("Saga snapshot state fields are invalid");
        }
        // protobuf uint64 超出 Java long 上界后表现为负值，不能把不可表示的版本当成合法顺序证据。
        if (stateVersion < 0 || controlVersion < 0) {
            throw new SagaProtocolException("Saga snapshot versions must be nonnegative");
        }
        if (currentStep != null) {
            SagaIds.requireStructured(currentStep, "current_step");
        }
        if (traceparent != null && SagaTraceparent.validOrNull(traceparent) == null) {
            throw new SagaProtocolException("Saga snapshot traceparent is invalid");
        }
    }
}
