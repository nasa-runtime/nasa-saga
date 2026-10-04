package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

/**
 * Rust managed HTTP 的实例审计分页条件。
 *
 * @param tenantId  租户身份
 * @param sagaId    Saga 实例身份
 * @param pageSize  返回规模，可为空
 * @param pageToken Rust opaque page token，可为空
 */
public record SagaAuditRequest(String tenantId, String sagaId, Integer pageSize, String pageToken) {

    /**
     * 业务作用：在构造签名 path 前复验审计目标身份，防止把未编码的路由控制字符送入验签。
     */
    public SagaAuditRequest {
        SagaIds.requireOpaque(tenantId, "tenant_id", 256);
        SagaIds.requireOpaque(sagaId, "saga_id", 256);
        if (pageSize != null && (pageSize < 1 || pageSize > 1000)) {
            throw new SagaProtocolException("audit page_size must be between 1 and 1000");
        }
        if (pageToken != null && pageToken.isBlank()) {
            throw new SagaProtocolException("audit page_token must not be blank");
        }
    }
}
