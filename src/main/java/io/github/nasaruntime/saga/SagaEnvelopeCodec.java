package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaCommandEnvelope;
import io.github.nasaruntime.saga.rc.SagaPayload;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Saga JSON envelope 的唯一编解码入口。
 */
public final class SagaEnvelopeCodec {

    // Rust 的递归预算为 128，进入容器后余额为零即拒绝；根对象和数组也占一个层级。
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(127).build())
            .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(127).build())
            // 非有限数不得转换成合法字符串；生成的数值 token 必须继续经过共享入站合同检查。
            .disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper(JSON_FACTORY)
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(JSON_FACTORY)
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * 业务作用：禁止构造无状态的协议编解码工具。
     * 参数说明：无。
     * 返回：仅供类内部使用。
     */
    private SagaEnvelopeCodec() {}

    /**
     * 业务作用：把 Java envelope 编码为 Rust transport 使用的 JSON，拒绝无法保持类型的数值和超深正文。
     *
     * @param value 待编码的 envelope
     * @return 符合字节、字符、数值及深度合同的 UTF-8 JSON；不合规时抛出协议异常，不返回可发送正文
     */
    public static byte[] encode(Object value) {
        try {
            byte[] encoded = MAPPER.writeValueAsBytes(value);
            // 内存构造的 JsonNode、Map 或 DTO 同样可能含非有限数；必须在网络发送或 intent 持久化前拒绝。
            jsonText(encoded);
            return encoded;
        } catch (IOException exception) {
            throw new SagaProtocolException("Saga envelope encoding failed", exception);
        }
    }

    /**
     * 业务作用：校验 Rust JSON 的字节和字符合同后，按兼容字段策略解码指定 envelope 类型。
     *
     * @param payload UTF-8 JSON 字节
     * @param type    目标 envelope 类型
     * @param <T>     目标类型
     * @return 解码后的对象；字节、Unicode、数值或深度非法时拒绝，不替换或规范化原文
     */
    public static <T> T decode(byte[] payload, Class<T> type) {
        try {
            return MAPPER.readValue(jsonText(payload), type);
        } catch (IOException exception) {
            throw new SagaProtocolException("Saga envelope decoding failed", exception);
        }
    }

    /**
     * 业务作用：在类型转换前核验协议字段的 JSON 类型与唯一性，防止歧义身份进入业务或成功收据。
     *
     * @param payload UTF-8 JSON 字节
     * @param type    目标类型
     * @param <T>     目标类型
     * @return 协议根与字段校验、DTO 解码均通过后的非空对象；可选字段与业务 JSON 保留自身的值语义
     * @throws SagaProtocolException 字节、Unicode、数值或深度非法、根值为 null、协议字段重复、类型不符或包含未知字段
     */
    public static <T> T decodeStrict(byte[] payload, Class<T> type) {
        String text = jsonText(payload);
        try (JsonParser parser = STRICT_MAPPER.createParser(text)) {
            // 必须在绑定 DTO 前检查原始 token；绑定后浮点截断、标量转换和重复键覆盖已不可辨认。
            parser.nextToken();
            // 可选字段允许 null，不代表协议根可缺失；明确拒绝才能保持入站与持久化隔离的确定性分类。
            if (parser.currentToken() == JsonToken.VALUE_NULL) {
                throw new SagaProtocolException("Saga protocol root cannot be null");
            }
            validateProtocolValue(parser, STRICT_MAPPER.constructType(type));
            return STRICT_MAPPER.readValue(text, type);
        } catch (IOException exception) {
            throw new SagaProtocolException("Saga strict JSON decoding failed", exception);
        }
    }

    /**
     * 业务作用：统一外层协议与独立 JSON 正文的字节、Unicode、数值和容器深度边界，保持原字节不变。
     *
     * @param payload 原始 JSON 字节，不适用于非 JSON 媒体正文
     * @return 仅供解析的文本；编码、字符、数值或深度非法时抛出协议异常，不改写原文
     */
    private static String jsonText(byte[] payload) {
        if (payload == null) throw new SagaProtocolException("Saga JSON bytes are required");
        try {
            // 固定使用严格 UTF-8，再交给字符解析器；禁止自动识别 UTF-16/32 或吞掉 BOM 改变跨语言裁决。
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                throw new SagaProtocolException("Saga JSON must not have a BOM");
            }
            // 必须在 DTO 绑定和业务重复键覆盖前检查每个原始 token，不能漏掉被覆盖或未绑定的字符串。
            try (JsonParser parser = STRICT_MAPPER.createParser(text)) {
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING) {
                        requireUnicodeScalars(parser.getText());
                    } else if (token.isNumeric()) {
                        // 溢出值即使稍后被同名业务字段覆盖也必须拒绝，不能等 JsonNode 绑定后再检查。
                        requireFiniteNumber(parser.getText());
                    }
                }
            }
            return text;
        } catch (IOException exception) {
            throw new SagaProtocolException("Saga JSON value contract is invalid", exception);
        }
    }

    /**
     * 业务作用：按 Rust 默认 JSON 数值转换边界拒绝溢出，不把大整数限制为有符号 long，也不拒绝下溢或零。
     *
     * @param token 已由 JSON parser 确认语法的原始数值文本
     *              返回：有限数值通过；超出 Rust 数值域或会在 Java 中形成非有限数时抛出协议异常，不做摘要规范化。
     */
    private static void requireFiniteNumber(String token) {
        int index = token.charAt(0) == '-' ? 1 : 0;
        long significand = 0;
        long exponent = 0;
        boolean decimal = false;
        boolean truncated = false;
        while (index < token.length()) {
            char character = token.charAt(index);
            if (character == 'e' || character == 'E') break;
            index++;
            if (character == '.') {
                decimal = true;
                truncated = false;
                continue;
            }
            int digit = character - '0';
            // Rust 先积累 u64 有效位，整数溢出后只累计十进位，小数溢出后忽略后续小数位。
            if (!truncated && (Long.compareUnsigned(significand, 1844674407370955161L) > 0
                    || (significand == 1844674407370955161L && digit > 5))) {
                truncated = true;
            }
            if (!truncated) {
                significand = significand * 10 + digit;
                if (decimal) exponent--;
            } else if (!decimal) {
                exponent++;
            }
        }
        if (index < token.length()) {
            index++;
            boolean negativeExponent = token.charAt(index) == '-';
            if (negativeExponent || token.charAt(index) == '+') index++;
            long explicitExponent = 0;
            while (index < token.length()) {
                explicitExponent = Math.min(2147483648L, explicitExponent * 10 + token.charAt(index++) - '0');
            }
            // 指数超出 i32 时 Rust 仍允许零及负指数下溢；正指数且非零才是不可表示的业务数值。
            if (explicitExponent > Integer.MAX_VALUE) {
                if (!negativeExponent && significand != 0) {
                    throw new SagaProtocolException("Saga JSON number is outside the finite range");
                }
                return;
            }
            exponent += negativeExponent ? -explicitExponent : explicitExponent;
        }
        if (significand != 0 && exponent > 0) {
            // u64 到 f64 再乘十次幂与 Rust 默认解析顺序一致，不能只依赖 Java 的一次性十进制舍入。
            double value = Double.parseDouble(Long.toUnsignedString(significand));
            if (exponent > 308 || !Double.isFinite(value * Double.parseDouble("1e" + exponent))) {
                throw new SagaProtocolException("Saga JSON number is outside the finite range");
            }
        }
        if (!Double.isFinite(Double.parseDouble(token))) {
            throw new SagaProtocolException("Saga JSON number cannot form a finite Java value");
        }
    }

    /**
     * 业务作用：校验 JSON 转义后的字段名和字符串，确保每个 UTF-16 代理项组成合法 Unicode 标量。
     *
     * @param value JSON parser 解码后的单个字段名或字符串
     *              返回：合法 BMP 字符和代理对原样通过；孤立高位或低位代理项抛出协议异常。
     */
    private static void requireUnicodeScalars(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new SagaProtocolException("Saga JSON contains an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new SagaProtocolException("Saga JSON contains an unpaired surrogate");
            }
        }
    }

    /**
     * 业务作用：沿 DTO 类型边界核对原始 token，拒绝协议对象重复字段及标量隐式转换。
     *
     * @param parser 当前位于待校验值的原始 JSON 解析器
     * @param type   该值对应的协议类型
     *               返回：消耗当前完整值；协议字段歧义或类型不符时抛出异常，不生成业务对象。
     * @throws IOException 原始 JSON 无法完整解析
     */
    private static void validateProtocolValue(JsonParser parser, JavaType type) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == null) throw new SagaProtocolException("Saga protocol value is missing");
        if (token == JsonToken.VALUE_NULL) {
            if (type.isPrimitive()) throw new SagaProtocolException("Saga primitive protocol field cannot be null");
            return;
        }
        Class<?> raw = type.getRawClass();
        // serde_json::Value 与业务 Map 的键不是 DTO 字段，业务重复键仍按后值覆盖，不施加协议对象规则。
        if (JsonNode.class.isAssignableFrom(raw) || raw == Object.class) {
            parser.skipChildren();
            return;
        }
        boolean integer = raw == byte.class || raw == short.class || raw == int.class || raw == long.class
                || raw == Byte.class || raw == Short.class || raw == Integer.class || raw == Long.class
                || raw == BigInteger.class;
        if ((integer && token != JsonToken.VALUE_NUMBER_INT)
                || ((raw == String.class || type.isEnumType()) && token != JsonToken.VALUE_STRING)
                || ((raw == boolean.class || raw == Boolean.class)
                && token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE)
                || ((raw == float.class || raw == double.class || raw == Float.class || raw == Double.class
                || raw == BigDecimal.class) && !token.isNumeric())) {
            throw new SagaProtocolException("Saga protocol scalar type does not match JSON token");
        }
        if (type.isArrayType() || type.isCollectionLikeType()) {
            if (token != JsonToken.START_ARRAY) throw new SagaProtocolException("Saga protocol array is required");
            while (parser.nextToken() != JsonToken.END_ARRAY) validateProtocolValue(parser, type.getContentType());
            return;
        }
        if (type.isMapLikeType()) {
            if (token != JsonToken.START_OBJECT) throw new SagaProtocolException("Saga protocol map is required");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                parser.nextToken();
                validateProtocolValue(parser, type.getContentType());
            }
            return;
        }
        if (token != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        Map<String, JavaType> properties = new HashMap<>();
        for (BeanPropertyDefinition property : STRICT_MAPPER.getDeserializationConfig().introspect(type).findProperties()) {
            properties.put(property.getName(), property.getPrimaryType());
        }
        Set<String> fields = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME)
                throw new SagaProtocolException("Saga protocol field is required");
            String field = parser.currentName();
            // 协议字段重复即有歧义，即使两次值相同也不能依赖后值覆盖来决定业务身份。
            if (!fields.add(field)) throw new SagaProtocolException("Saga protocol contains a duplicate field");
            JavaType propertyType = properties.get(field);
            // raw_payload 的外壳属于 SagaPayload 协议；只有其 body 字节解码后的业务 JSON 使用 Value 语义。
            if (raw == SagaCommandEnvelope.class && "raw_payload".equals(field)) {
                propertyType = STRICT_MAPPER.constructType(SagaPayload.class);
            }
            parser.nextToken();
            if (propertyType == null) parser.skipChildren();
            else validateProtocolValue(parser, propertyType);
        }
    }

    /**
     * 业务作用：按 Rust JSON 字节、Unicode、数值、深度和完整正文合同解析业务值，保留合法重复键覆盖语义。
     *
     * @param payload 待解析的原始 JSON 正文
     * @return 完整正文对应的 JSON 值；编码、字符、数值、深度、空正文或尾随内容非法时拒绝
     */
    public static JsonNode decodeJsonStrict(byte[] payload) {
        try {
            JsonNode value = STRICT_MAPPER.readTree(jsonText(payload));
            if (value == null || value.isMissingNode()) {
                throw new SagaProtocolException("Saga JSON payload is empty");
            }
            return value;
        } catch (IOException exception) {
            throw new SagaProtocolException("Saga JSON payload decoding failed", exception);
        }
    }
}
