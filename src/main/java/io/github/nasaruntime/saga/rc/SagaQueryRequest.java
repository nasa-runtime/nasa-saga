package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Rust managed HTTP 的实例查询条件。
 *
 * @param tenantId      租户身份
 * @param workflow      可选 workflow
 * @param statuses      远端状态文本集合
 * @param createdFromMs 创建时间下界，可为空
 * @param createdToMs   创建时间上界，可为空
 * @param afterSagaId   不带时间范围时使用的 keyset 起点，可为空
 * @param pageToken     Rust opaque page token，可为空
 * @param pageSize      返回规模，可为空以使用 Rust 默认值
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SagaQueryRequest(
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("workflow") String workflow,
        @JsonProperty("statuses") List<String> statuses,
        @JsonProperty("created_from_ms") Long createdFromMs,
        @JsonProperty("created_to_ms") Long createdToMs,
        @JsonProperty("after_saga_id") String afterSagaId,
        @JsonProperty("page_token") String pageToken,
        @JsonProperty("page_size") Integer pageSize) {

    /**
     * 业务作用：在签名 query body 发出前复验 Rust 的分页组合和租户边界。
     */
    public SagaQueryRequest {
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        if (workflow != null) {
            SagaIds.requireStructured(workflow, "workflow");
        }
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        for (String status : statuses) {
            if (status == null || status.isBlank() || status.length() > 64
                    || status.chars().anyMatch(Character::isISOControl)) {
                throw new SagaProtocolException("query status is not bounded");
            }
        }
        if (createdFromMs != null && createdToMs != null && createdFromMs > createdToMs) {
            throw new SagaProtocolException("query time range is reversed");
        }
        if (afterSagaId != null && pageToken != null) {
            throw new SagaProtocolException("after_saga_id and page_token are mutually exclusive");
        }
        if ((createdFromMs != null || createdToMs != null) && afterSagaId != null) {
            throw new SagaProtocolException("time range and after_saga_id are mutually exclusive");
        }
        if (afterSagaId != null) {
            SagaIds.requireOpaque(afterSagaId, "after_saga_id", 256);
        }
        if (pageToken != null && pageToken.isBlank()) {
            throw new SagaProtocolException("page_token must not be blank");
        }
        if (pageSize != null && (pageSize < 1 || pageSize > 1000)) {
            throw new SagaProtocolException("page_size must be between 1 and 1000");
        }
    }
}
