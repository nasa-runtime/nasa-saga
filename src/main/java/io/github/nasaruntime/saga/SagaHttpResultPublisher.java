package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaHttpDeliveryReceipt;
import io.github.nasaruntime.saga.rc.SagaHttpResponse;
import io.github.nasaruntime.saga.rc.SagaResultEnvelope;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Java participant 向 Rust Orchestrator `/results` 投递结果的 HTTP adapter。
 */
public final class SagaHttpResultPublisher {

    private final SagaHttpTransport transport;

    /**
     * 业务作用：绑定 Result credential 对应的 HTTP transport，避免把 Api 或 Command key 用于结果投递。
     *
     * @param transport 已绑定 Result producer 与 base path 的 HTTP transport
     */
    public SagaHttpResultPublisher(SagaHttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * 业务作用：以稳定 event_id 和原始 result envelope 投递已提交的本地业务事实。
     *
     * @param result      已通过身份与 phase 校验的结果 envelope
     * @param traceparent 可选链路上下文
     * @return Rust transport receipt；只有 Committed/Duplicate 可前移本地 Outbox
     */
    public SagaHttpDeliveryReceipt publish(SagaResultEnvelope result, String traceparent) {
        Objects.requireNonNull(result, "result");
        byte[] body = result.encode();
        SagaHttpResponse response;
        try {
            response = transport.exchange("POST", "/results", body, result.eventId(), traceparent);
        } catch (SagaHttpException exception) {
            if (exception.statusCode() == 422) {
                return new SagaHttpDeliveryReceipt(SagaReceiptKind.DETERMINISTIC_REJECT);
            }
            throw exception;
        }
        String contentType = response.firstHeader("content-type");
        if (contentType == null) {
            throw new SagaHttpException(
                    SagaHttpDisposition.UNCERTAIN,
                    response.statusCode(),
                    response.body(),
                    "Saga result receipt is not JSON",
                    null);
        }
        String mediaType = contentType.split(";", 2)[0].trim();
        if (!"application/json".equalsIgnoreCase(mediaType)) {
            throw new SagaHttpException(
                    SagaHttpDisposition.UNCERTAIN,
                    response.statusCode(),
                    new byte[0],
                    "Saga HTTP result receipt is not JSON",
                    null);
        }
        HttpReceipt receipt;
        try {
            receipt = SagaEnvelopeCodec.decodeStrict(response.body(), HttpReceipt.class);
        } catch (SagaProtocolException exception) {
            throw new SagaHttpException(
                    SagaHttpDisposition.UNCERTAIN,
                    response.statusCode(),
                    new byte[0],
                    "Saga HTTP result receipt is invalid",
                    exception);
        }
        return switch (receipt.status()) {
            case "Committed" -> new SagaHttpDeliveryReceipt(SagaReceiptKind.COMMITTED);
            case "Duplicate" -> new SagaHttpDeliveryReceipt(SagaReceiptKind.DUPLICATE);
            case "DeterministicReject" -> new SagaHttpDeliveryReceipt(SagaReceiptKind.DETERMINISTIC_REJECT);
            default -> throw new SagaHttpException(
                    SagaHttpDisposition.UNCERTAIN,
                    response.statusCode(),
                    new byte[0],
                    "Saga HTTP result receipt has an unsupported status",
                    null);
        };
    }

    private record HttpReceipt(@JsonProperty("status") String status) {
    }
}
