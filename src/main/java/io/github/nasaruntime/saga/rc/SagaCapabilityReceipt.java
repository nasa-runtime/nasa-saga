package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rust Catalog 返回的 capability lease 收据。
 *
 * @param acceptedUntilMs   Rust 数据库时钟确定的租约截止时间
 * @param capabilityDigest  不含 lease 建议的 descriptor 摘要
 * @param routeGeneration   Rust Catalog 分配的路由代际
 * @param catalogGeneration HTTP 收据的正 Catalog generation；gRPC 未提供此字段，按 Rust adapter 合同为零
 */
public record SagaCapabilityReceipt(
        @JsonProperty("accepted_until_ms") long acceptedUntilMs,
        @JsonProperty("capability_digest") String capabilityDigest,
        @JsonProperty("route_generation") long routeGeneration,
        @JsonProperty("catalog_generation") long catalogGeneration) {

    /**
     * 业务作用：拒绝缺少服务端租约、摘要或路由代际的 Registry 响应，避免 participant 提前开放路由。
     * 参数说明：显式参数对应收据字段；catalogGeneration 的零值仅供未提供此字段的 gRPC 投影使用。
     * 返回：合法共享收据；负时间、非法摘要或代际抛出协议异常，HTTP 必需字段由其 adapter 额外验证。
     */
    public SagaCapabilityReceipt {
        if (acceptedUntilMs < 0 || routeGeneration <= 0 || catalogGeneration < 0) {
            throw new SagaProtocolException("Saga capability receipt counters are invalid");
        }
        SagaIds.requireDigest(capabilityDigest);
    }

    /**
     * 业务作用：确认登记收据尚未到期且确实绑定本次 participant 的完整路由合同。
     *
     * @param descriptor 本次发送的 capability，不以收据反向替换本地身份或地址
     * @param nowMs      当前本地 Unix 毫秒时间
     *                   返回：有效收据通过；过期或摘要不匹配时抛出协议异常，宿主不得据此开放 command。
     */
    public void validateFor(SagaCapabilityDescriptor descriptor, long nowMs) {
        if (descriptor == null || nowMs < 0 || acceptedUntilMs <= nowMs) {
            throw new SagaProtocolException("capability receipt lease has expired");
        }
        // 服务端分配代际后重新计算摘要，避免把其它 endpoint 或旧合同的成功响应当成路由权威。
        if (!descriptor.digestForRoute(routeGeneration).equals(capabilityDigest)) {
            throw new SagaProtocolException("capability receipt does not match the registered route");
        }
    }
}
