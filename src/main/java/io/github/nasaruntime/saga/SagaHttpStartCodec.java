package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaPayload;
import io.github.nasaruntime.saga.rc.SagaStartRequest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Rust managed HTTP start body 和 Java 本地 intent 摘要的共享编码入口。
 */
public final class SagaHttpStartCodec {

    /**
     * 业务作用：禁止构造无状态的 start 编解码工具。
     * 参数说明：无。
     * 返回：仅类内部使用。
     */
    private SagaHttpStartCodec() {}

    /**
     * 业务作用：生成最终发送给 Rust `POST /instances` 的固定字段 JSON，供 direct 与 reliable 共用。
     *
     * @param request start 请求
     * @return UTF-8 raw JSON body
     */
    public static byte[] encode(SagaStartRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        request.validate();
        return SagaEnvelopeCodec.encode(new HttpStartBody(
                request.tenantId(),
                request.sagaId(),
                request.workflow(),
                request.definitionVersion(),
                request.expectedDefinitionDigest() == null || request.expectedDefinitionDigest().isEmpty()
                        ? null : request.expectedDefinitionDigest(),
                request.businessKey(),
                request.triggerId(),
                request.deadline() == null ? null : request.deadline().toEpochMilli(),
                request.input(),
                request.payload() == null ? null : HttpPayload.from(request.payload())));
    }

    /**
     * 业务作用：严格恢复冻结的 start JSON，供 gRPC 或业务 facade 使用相同字段与原始 payload 字节。
     *
     * @param body        与 Rust HTTP start 合同相同的 UTF-8 JSON
     * @param traceparent 独立保存、不参与请求摘要的链路上下文
     * @return 通过身份、互斥正文和期限校验的请求；未知字段或非法字节拒绝。
     */
    public static SagaStartRequest decode(byte[] body, String traceparent) {
        HttpStartBody value = SagaEnvelopeCodec.decodeStrict(body, HttpStartBody.class);
        SagaPayload payload = null;
        if (value.payload() != null) {
            if (value.payload().body() == null) throw new SagaProtocolException("payload body is required");
            byte[] bytes = new byte[value.payload().body().length];
            for (int index = 0; index < bytes.length; index++) {
                int item = value.payload().body()[index];
                if (item < 0 || item > 255) throw new SagaProtocolException("payload byte is outside u8 range");
                bytes[index] = (byte) item;
            }
            payload = new SagaPayload(value.payload().contentType(), value.payload().schemaId(), bytes);
        }
        SagaStartRequest request = new SagaStartRequest(value.tenantId(), value.sagaId(), value.workflow(),
                value.definitionVersion(), value.expectedDefinitionDigest(), value.businessKey(), value.triggerId(),
                value.input() == null || value.input().isNull() ? null : value.input(), payload,
                value.deadlineAtMs() == null ? null : Instant.ofEpochMilli(value.deadlineAtMs()), traceparent);
        request.validate();
        return request;
    }

    /**
     * 业务作用：保存 raw body 的完整性摘要，不把本地摘要冒充 Rust start_request_digest。
     *
     * @param body 最终发送的 raw body
     * @return 小写 SHA-256 文本
     */
    public static String sha256Hex(byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("body is required");
        }
        try {
            return HexFormat.of().withLowerCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required", exception);
        }
    }

    /**
     * 业务作用：为本地唯一索引生成长度前缀 tuple 摘要，避免数据库截断文本后误判业务槽位。
     *
     * @param fields 按业务合同排列的 UTF-8 文本字段
     * @return 32 字节 SHA-256 摘要
     */
    public static byte[] tupleDigest(String... fields) {
        if (fields == null || fields.length == 0) {
            throw new IllegalArgumentException("tuple fields are required");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String field : fields) {
            if (field == null) {
                throw new SagaProtocolException("tuple field is required");
            }
            byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(ByteBuffer.allocate(Long.BYTES)
                    .order(ByteOrder.BIG_ENDIAN).putLong(encoded.length).array());
            bytes.writeBytes(encoded);
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required", exception);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record HttpStartBody(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("saga_id") String sagaId,
            @JsonProperty("workflow") String workflow,
            @JsonProperty("definition_version") int definitionVersion,
            @JsonProperty("expected_definition_digest") String expectedDefinitionDigest,
            @JsonProperty("business_key") String businessKey,
            @JsonProperty("trigger_id") String triggerId,
            @JsonProperty("deadline_at_ms") Long deadlineAtMs,
            @JsonProperty("input") JsonNode input,
            @JsonProperty("payload") HttpPayload payload) {
    }

    /**
     * Rust serde 将 Vec<u8> 编码为 JSON 数字数组；不能让 Jackson 把 byte[] 编成 base64 字符串。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record HttpPayload(
            @JsonProperty("content_type") String contentType,
            @JsonProperty("schema_id") String schemaId,
            @JsonProperty("body") int[] body) {

        /**
         * 业务作用：把冻结的有符号 Java 字节投影为 Rust `Vec<u8>` 的无符号 JSON 数组。
         *
         * @param payload 已通过正文合同校验的业务正文
         * @return 与 Rust `SagaPayload` serde 形状一致的 HTTP DTO
         */
        private static HttpPayload from(SagaPayload payload) {
            byte[] rawBody = payload.body();
            int[] body = new int[rawBody.length];
            for (int index = 0; index < rawBody.length; index++) {
                body[index] = Byte.toUnsignedInt(rawBody[index]);
            }
            return new HttpPayload(payload.contentType(), payload.schemaId(), body);
        }
    }
}
