package io.github.nasaruntime.saga;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.Locale;

/**
 * Rust `nasaga-core` 身份派生规则的 Java 实现。
 *
 * <p>这些身份是跨进程幂等边界的一部分，不能改用 Java 随机 UUID 或字符串拼接规则替代。
 */
public final class SagaIds {

    private static final byte[] SAGA_ID_NAMESPACE = "nasasaga-v1-idns".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESULT_EVENT_NAMESPACE = "nasasaga-v1-rsns".getBytes(StandardCharsets.US_ASCII);

    /**
     * 业务作用：限制身份派生与校验为无状态入口，避免保存可变业务身份。
     * 参数说明：无。
     */
    private SagaIds() {}

    /**
     * 业务作用：按 Rust 固定命名空间派生跨 attempt 稳定的业务效果身份。
     *
     * @param sagaId            Saga 实例身份
     * @param definitionVersion 实例冻结的 definition 版本
     * @param step              步骤名称
     * @param phase             阶段名称，取 execute、cancel、compensate 或 resolve
     * @return 与 Rust `EffectId::derive` 完全一致的 UUID 文本
     */
    public static String effectId(String sagaId, int definitionVersion, String step, String phase) {
        requireOpaque(sagaId, "saga_id", 256);
        requirePositive(definitionVersion, "definition_version");
        requireStructured(step, "step");
        requirePhase(phase);
        byte[] name = canonical(
                sagaId.getBytes(StandardCharsets.UTF_8),
                intBytes(definitionVersion),
                step.getBytes(StandardCharsets.UTF_8),
                phase.getBytes(StandardCharsets.US_ASCII));
        return uuidV5(SAGA_ID_NAMESPACE, name).toString();
    }

    /**
     * 业务作用：按 Rust 固定命名空间派生单次投递尝试的命令身份。
     *
     * @param effectId 跨 attempt 稳定的业务效果 UUID 文本
     * @param attempt  本次投递尝试序号，从 1 开始
     * @return 与 Rust `CommandId::derive` 完全一致的 UUID 文本
     */
    public static String commandId(String effectId, int attempt) {
        UUID effect = parseUuid(effectId, "effect_id");
        requirePositive(attempt, "attempt");
        byte[] name = canonical(uuidBytes(effect), intBytes(attempt));
        return uuidV5(SAGA_ID_NAMESPACE, name).toString();
    }

    /**
     * 业务作用：为同一 command 确定性派生 result event 身份，使结果重投仍命中同一个 Inbox。
     *
     * @param commandId command 身份文本
     * @return 与 Rust `derive_result_event_id` 完全一致的 UUID 文本
     */
    public static String resultEventId(String commandId) {
        parseUuid(commandId, "command_id");
        byte[] name = canonical(
                commandId.getBytes(StandardCharsets.UTF_8),
                "result".getBytes(StandardCharsets.US_ASCII));
        return uuidV5(RESULT_EVENT_NAMESPACE, name).toString();
    }

    /**
     * 业务作用：校验 Rust Saga 使用的结构性名称，阻止名称进入 topic、指标和路由时产生歧义。
     *
     * @param value 待校验名称
     * @param field 字段名，用于构造脱敏错误
     * @return 原值
     */
    public static String requireStructured(String value, String field) {
        if (value == null || value.isEmpty() || !value.equals(value.strip())
                || value.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new SagaProtocolException(field + " is not a valid structured identifier");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '_' && character != '-' && character != '.') {
                throw new SagaProtocolException(field + " contains an unsupported character");
            }
        }
        return value;
    }

    /**
     * 业务作用：校验 Saga 的不透明身份字段，避免控制字符和超长值破坏审计、存储或幂等键。
     *
     * @param value    待校验值
     * @param field    字段名，用于构造脱敏错误
     * @param maxBytes 字段字节上限
     * @return 原值
     */
    public static String requireOpaque(String value, String field, int maxBytes) {
        if (value == null || value.isEmpty() || !value.equals(value.strip())
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new SagaProtocolException(field + " is not a valid opaque identifier");
        }
        return value;
    }

    /**
     * 业务作用：校验 definition digest 的规范传输形式，防止同一内容出现大小写不同的身份。
     *
     * @param digest definition 内容 SHA-256 摘要
     * @return 原摘要
     */
    public static String requireDigest(String digest) {
        if (digest == null || digest.length() != 64
                || !digest.equals(digest.toLowerCase(Locale.ROOT))
                || !digest.chars().allMatch(character -> character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f')) {
            throw new SagaProtocolException("definition_digest is not lowercase SHA-256 hex");
        }
        return digest;
    }

    /**
     * 业务作用：校验原因码能否安全进入 Rust 的持久化列、指标标签和结果 envelope。
     *
     * @param reasonCode 原因码
     * @return 原因码
     */
    public static String requireReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isEmpty() || reasonCode.length() > 64
                || !reasonCode.chars().allMatch(character -> character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '_' || character == '-' || character == '.')) {
            throw new SagaProtocolException("reason_code is not a bounded stable identifier");
        }
        return reasonCode;
    }

    /**
     * 业务作用：校验持久 identity 使用 Rust `Uuid::to_string` 的小写规范文本。
     *
     * @param value UUID 文本
     * @param field 字段名
     * @return 原始规范文本
     */
    public static String requireUuid(String value, String field) {
        if (value == null || value.length() != 36) {
            throw new SagaProtocolException(field + " is not a canonical UUID");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new SagaProtocolException(field + " is not a lowercase canonical UUID");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new SagaProtocolException(field + " is not a canonical UUID", exception);
        }
    }

    /**
     * 业务作用：以 UTF-8 字节数校验跨语言持久字段，避免 Java 字符数与数据库索引边界不一致。
     *
     * @param value 待计算文本
     * @return UTF-8 字节数
     */
    public static int utf8Length(String value) {
        if (value == null) {
            throw new SagaProtocolException("text value is required");
        }
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 业务作用：校验阶段名称，确保 Java 与 Rust 使用同一个身份命名空间。
     *
     * @param phase 阶段名称
     * @return 原阶段名称
     */
    public static String requirePhase(String phase) {
        if (!"execute".equals(phase) && !"cancel".equals(phase)
                && !"compensate".equals(phase) && !"resolve".equals(phase)) {
            throw new SagaProtocolException("phase is not a supported Saga phase");
        }
        return phase;
    }

    /**
     * 业务作用：校验必须从 1 开始的 definition version 和 attempt counter。
     *
     * @param value counter 值
     * @param field 字段名
     */
    public static void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new SagaProtocolException(field + " must be greater than zero");
        }
    }

    /**
     * 业务作用：按 Rust canonical length-prefix 规则编码身份字段，消除不同字段组合的边界歧义。
     *
     * @param fields 按协议顺序排列的字段原始字节
     * @return 每个字段带八字节大端长度前缀的规范字节串
     */
    private static byte[] canonical(byte[]... fields) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (byte[] field : fields) {
            encoded.writeBytes(longBytes(field.length));
            encoded.writeBytes(field);
        }
        return encoded.toByteArray();
    }

    /**
     * 业务作用：把 u32 语义的 version/attempt 编码为四字节大端值。
     *
     * @param value 非负计数值
     * @return 四字节大端编码
     */
    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    /**
     * 业务作用：把字段长度编码为 Rust canonical 规则要求的八字节大端值。
     *
     * @param value 字段字节长度
     * @return 八字节大端编码
     */
    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    /**
     * 业务作用：把 envelope 自报的 UUID 转为原始十六字节身份，非法值不得参与派生。
     *
     * @param value UUID 文本
     * @param field 字段名
     * @return 解析后的 UUID
     */
    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new SagaProtocolException(field + " is not a UUID", exception);
        }
    }

    /**
     * 业务作用：按 UUID 网络字节顺序编码 command 派生所需的 effect identity。
     *
     * @param value UUID
     * @return 十六字节大端 UUID 表示
     */
    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    /**
     * 业务作用：执行与 Rust uuid::Uuid::new_v5 相同的 SHA-1 namespace 派生并设置 UUIDv5 位。
     *
     * @param namespace 固定的十六字节 namespace
     * @param name      canonical 派生输入
     * @return 确定性 UUIDv5
     */
    private static UUID uuidV5(byte[] namespace, byte[] name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(namespace);
            byte[] bytes = digest.digest(name);
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-1 is required for UUIDv5 compatibility", exception);
        }
    }
}
