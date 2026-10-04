package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaEnvelopeCodec;
import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Saga command 使用的原始正文合同。
 *
 * @param contentType 规范媒体类型
 * @param schemaId    稳定 schema 身份；无 schema JSON 使用空字符串
 * @param body        未重新编码的原始业务字节
 */
public record SagaPayload(
        @JsonProperty("content_type") String contentType,
        @JsonProperty("schema_id") String schemaId,
        @JsonProperty("body")
        @JsonSerialize(using = RawBodySerializer.class)
        @JsonDeserialize(using = RawBodyDeserializer.class)
        byte[] body) {

    /**
     * 业务作用：冻结跨 transport 传递的正文，防止重试或业务代码改变签名和 schema 依据。
     */
    public SagaPayload {
        if (contentType == null || schemaId == null || body == null
                || contentType.isBlank() || !contentType.equals(contentType.strip())
                || SagaIds.utf8Length(contentType) > 128 || !validContentType(contentType)
                || SagaIds.utf8Length(schemaId) > 256
                || !schemaId.equals(schemaId.strip())
                || schemaId.chars().anyMatch(Character::isISOControl)
                || (!"application/json".equals(contentType) && schemaId.isEmpty())
                || ("application/json".equals(contentType) && body.length == 0)) {
            throw new SagaProtocolException("Saga payload is incomplete");
        }
        if ("application/json".equals(contentType)) {
            SagaEnvelopeCodec.decodeJsonStrict(body);
        }
        body = body.clone();
    }

    /**
     * 业务作用：返回正文副本，保证业务 handler 不能改变 Outbox 或审计使用的原始字节。
     *
     * @return 原始正文副本
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * 业务作用：判断正文是否为宏声明的默认 JSON 合同。
     *
     * @return 媒体类型为 application/json 且 schema 为空时返回真
     */
    public boolean isDefaultJson() {
        return "application/json".equals(contentType) && schemaId.isEmpty();
    }

    /**
     * 业务作用：比较正文原始字节，避免数组引用比较造成跨尝试身份误判。
     *
     * @param other 另一个正文
     * @return 三个正文字段都一致时返回真
     */
    public boolean sameBytes(SagaPayload other) {
        return other != null && contentType.equals(other.contentType)
                && schemaId.equals(other.schemaId) && Arrays.equals(body, other.body);
    }

    /**
     * 业务作用：执行 Rust payload media type 的小写字符集校验，避免等价拼写产生不同合同。
     *
     * @param value 待校验媒体类型
     * @return 满足单一 kind/subtype 结构和允许字符集时返回真
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

    /**
     * Rust serde 对 Vec&lt;u8&gt; 使用数字数组；该序列化器保持原始字节，不允许 Jackson 默认转成 base64。
     */
    private static final class RawBodySerializer extends JsonSerializer<byte[]> {

        /**
         * 业务作用：把 Java 有符号字节逐项写成 Rust Vec&lt;u8&gt; 可读取的 0..255 数字数组。
         *
         * @param value       已冻结的原始正文
         * @param generator   JSON 输出目标
         * @param serializers Jackson 序列化上下文
         * @throws IOException JSON 输出失败
         */
        @Override
        public void serialize(byte[] value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeStartArray();
            for (byte item : value) {
                generator.writeNumber(Byte.toUnsignedInt(item));
            }
            generator.writeEndArray();
        }
    }

    /**
     * Rust serde 对 Vec&lt;u8&gt; 要求 0..255 的整数数组；该反序列化器拒绝 base64 和浮点隐式转换。
     */
    private static final class RawBodyDeserializer extends JsonDeserializer<byte[]> {

        /**
         * 业务作用：将 Rust payload 的数字数组恢复为冻结的 Java 原始字节。
         *
         * @param parser  JSON 输入
         * @param context Jackson 反序列化上下文
         * @return 原始正文副本
         * @throws IOException JSON token 非法或正文超出字节范围
         */
        @Override
        public byte[] deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.isExpectedStartArrayToken()) {
                return (byte[]) context.handleUnexpectedToken(byte[].class, parser);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                    return (byte[]) context.handleUnexpectedToken(byte[].class, parser);
                }
                int value = parser.getIntValue();
                if (value < 0 || value > 255) {
                    throw context.weirdNumberException(value, byte[].class,
                            "Saga payload body must contain integers from 0 to 255");
                }
                output.write(value);
            }
            return output.toByteArray();
        }
    }
}
