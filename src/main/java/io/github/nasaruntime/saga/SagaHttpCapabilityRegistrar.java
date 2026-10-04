package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaCapabilityDescriptor;
import io.github.nasaruntime.saga.rc.SagaCapabilityReceipt;
import io.github.nasaruntime.saga.rc.SagaHttpResponse;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Java participant 向 Rust Definition Catalog 登记或续租 HTTP capability 的适配器。
 */
public final class SagaHttpCapabilityRegistrar implements SagaCapabilityRegistrar {

    private final SagaHttpTransport transport;

    /**
     * 业务作用：绑定 Registry API credential 对应的 HTTP transport，隔离 capability 与 command/result 凭据。
     *
     * @param transport 已绑定 Registry API producer 和 Orchestrator base path 的 HTTP transport
     *                  返回：复用指定传输的登记适配器；transport 为空时拒绝构造。
     */
    public SagaHttpCapabilityRegistrar(SagaHttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * 业务作用：以 replica identity 幂等登记当前 participant 路由并返回 Rust 服务端租约。
     *
     * @param descriptor  当前 participant 的完整 capability descriptor
     * @param traceparent 可选链路上下文
     * @return 尚未过期且绑定当前 descriptor 的租约与正代际；缺失或损坏的响应以 UNCERTAIN 拒绝，不交付准入权威。
     */
    @Override
    public SagaCapabilityReceipt register(
            SagaCapabilityDescriptor descriptor,
            String traceparent) {
        Objects.requireNonNull(descriptor, "descriptor");
        descriptor.validateForHttpRegistration();
        SagaHttpResponse response = transport.exchange(
                "POST", "/registry/capabilities", SagaEnvelopeCodec.encode(descriptor), null, traceparent);
        String contentType = response.firstHeader("content-type");
        if (!isJsonContentType(contentType)) {
            throw new SagaHttpException(
                    SagaHttpDisposition.UNCERTAIN,
                    response.statusCode(),
                    new byte[0],
                    "Saga capability receipt is not JSON",
                    null);
        }
        try {
            SagaCapabilityReceipt receipt = SagaEnvelopeCodec.decodeStrict(response.body(), HttpCapabilityReceipt.class).toReceipt();
            // HTTP 必须提供真实 Catalog 代际；字段、租期和路由摘要同时通过才可交给宿主建立准入权威。
            receipt.validateFor(descriptor, System.currentTimeMillis());
            return receipt;
        } catch (SagaProtocolException | IllegalArgumentException exception) {
            throw new SagaHttpException(
                    SagaHttpDisposition.UNCERTAIN,
                    response.statusCode(),
                    new byte[0],
                    "Saga capability receipt is invalid",
                    exception);
        }
    }

    /**
     * 业务作用：防止非 JSON 成功页被误解释为 capability 收据。
     *
     * @param value 响应媒体类型，可为空
     * @return JSON 媒体类型返回 true，其它值返回 false
     */
    private static boolean isJsonContentType(String value) {
        if (value == null) {
            return false;
        }
        String mediaType = value.split(";", 2)[0].trim();
        return "application/json".equalsIgnoreCase(mediaType);
    }

    /**
     * HTTP 的四项准入证据均须显式存在，不能借用共享收据的 gRPC 零哨兵。
     */
    private record HttpCapabilityReceipt(
            @JsonProperty("accepted_until_ms") Long acceptedUntilMs,
            @JsonProperty("capability_digest") String capabilityDigest,
            @JsonProperty("route_generation") Long routeGeneration,
            @JsonProperty("catalog_generation") Long catalogGeneration) {

        /**
         * 业务作用：在 HTTP 解码阶段确认服务端租约和 Catalog 权威证据完整。
         *
         * @param acceptedUntilMs   数据库时钟确定的租约截止时间
         * @param capabilityDigest  服务端确认的完整路由摘要
         * @param routeGeneration   服务端分配的路由代际
         * @param catalogGeneration 本次登记所处的正 Catalog 代际
         *                          返回：字段完整且 Catalog 代际为正时保留原值；缺失、null 或非正代际抛出协议异常。
         */
        private HttpCapabilityReceipt {
            // 首次登记也会推进 Catalog 代际；缺失或零值不能作为解除 command 保护态的证据。
            if (acceptedUntilMs == null || capabilityDigest == null || routeGeneration == null
                    || catalogGeneration == null || catalogGeneration <= 0) {
                throw new SagaProtocolException("HTTP capability receipt authority is incomplete");
            }
        }

        /**
         * 业务作用：把 HTTP 已确认存在的字段映射为共享租约收据，继续执行摘要与计数值域合同。
         * 参数说明：无。
         *
         * @return 合法的共享收据；时间、摘要或路由代际非法时抛出协议异常。
         */
        private SagaCapabilityReceipt toReceipt() {
            return new SagaCapabilityReceipt(acceptedUntilMs, capabilityDigest, routeGeneration, catalogGeneration);
        }
    }
}
