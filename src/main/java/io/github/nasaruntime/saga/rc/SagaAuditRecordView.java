package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaProtocolException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Rust 审计记录的稳定公共投影；details 保留为 JSON，避免 Java 猜测不同 kind 的内部结构。
 *
 * @param auditId      审计事实身份
 * @param kind         非空事实类型，当前为 attempt、transition、control、management 或 conflict
 * @param stateVersion 非负关联状态版本，没有时为 0
 * @param actor        管理主体，可为空
 * @param reason       脱敏原因码，可为空
 * @param occurredAt   Rust 时间文本；HTTP 保留数据库投影，gRPC 使用 RFC3339
 * @param occurredAtMs 事实发生时刻的 epoch 毫秒
 * @param details      审计明细 JSON 对象，内部字段由事实类型定义
 */
public record SagaAuditRecordView(
        @JsonProperty("audit_id") String auditId,
        @JsonProperty("kind") String kind,
        @JsonProperty("state_version") long stateVersion,
        @JsonProperty("actor") String actor,
        @JsonProperty("reason") String reason,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("occurred_at_ms") Long occurredAtMs,
        @JsonProperty("details") JsonNode details) {

    /**
     * 业务作用：只向调用方交付包含稳定身份、类别、发生时间和明细的审计事实。
     * 参数说明：显式参数对应记录字段；actor、reason 和 stateVersion 允许各事实类型省略。
     * 返回：完整事实保留原值；必需字段缺失、版本为负或明细不是 JSON 对象时抛出协议异常。
     */
    public SagaAuditRecordView {
        // 身份不是 UUID，时间文本也因数据库而异；拒绝缺失证据，但不重写 Rust 的身份或事实内容。
        if (auditId == null || auditId.isBlank() || kind == null || kind.isBlank()
                || occurredAt == null || occurredAt.isBlank() || occurredAtMs == null
                || details == null || !details.isObject()) {
            throw new SagaProtocolException("Rust audit record is incomplete");
        }
        if (stateVersion < 0) {
            throw new SagaProtocolException("Rust audit state version must be nonnegative");
        }
    }
}
