package io.github.nasaruntime.saga;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Rust `SagaHttpMessageAuthenticator` 的 Java 实现。
 *
 * <p>签名只覆盖 Rust 合同规定的 producer、实际 path、时间、nonce 和原始 body。HTTP method、
 * content type、traceparent 和 event header 必须由调用方单独绑定到具体路由并复验。</p>
 */
public final class SagaHttpMessageAuthenticator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String MAC_PREFIX = "nasaga-http-v2\n";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of().withLowerCase();

    private final byte[] key;
    private final long maxClockSkewMillis;

    /**
     * 业务作用：从 Rust 配置使用的 64 位小写十六进制 key 建立不可变验签器。
     *
     * @param keyHex             恰好 64 个小写十六进制字符
     * @param maxClockSkewMillis 允许的 Unix 毫秒时钟偏差
     */
    public SagaHttpMessageAuthenticator(String keyHex, long maxClockSkewMillis) {
        this(decodeKey(keyHex), maxClockSkewMillis);
    }

    /**
     * 业务作用：绑定已由 secret provider 解码的 32 字节 key，避免重复解析配置文本。
     *
     * @param key                32 字节 HMAC key
     * @param maxClockSkewMillis 允许的 Unix 毫秒时钟偏差
     */
    public SagaHttpMessageAuthenticator(byte[] key, long maxClockSkewMillis) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("Saga HTTP key must contain 32 bytes");
        }
        if (maxClockSkewMillis <= 0) {
            throw new IllegalArgumentException("maxClockSkewMillis must be positive");
        }
        this.key = key.clone();
        this.maxClockSkewMillis = maxClockSkewMillis;
    }

    /**
     * 业务作用：为一次新的网络尝试计算 Rust 可验证的签名；重试必须传入新的 nonce 和时间。
     *
     * @param producer        由凭据绑定的逻辑 producer
     * @param path            Rust listener 实际看到的绝对 path
     * @param timestampMillis Unix 毫秒时间戳
     * @param nonce           32 个小写十六进制字符的一次性随机数
     * @param body            将要发送的原始 HTTP body
     * @return 64 个小写十六进制字符的 HMAC-SHA-256
     */
    public String sign(
            String producer,
            String path,
            long timestampMillis,
            String nonce,
            byte[] body) {
        validateSigningFields(producer, path, timestampMillis, nonce, body);
        return hex(mac(producer, path, timestampMillis, nonce, body));
    }

    /**
     * 业务作用：在 participant 进入 JSON 解码或业务事务前校验时间窗、格式、HMAC 和原始正文。
     *
     * @param producer        认证请求头中的 producer
     * @param path            listener 观察到的实际 path
     * @param timestampMillis 认证请求头中的 Unix 毫秒时间戳
     * @param nonce           认证请求头中的一次性随机数
     * @param signature       认证请求头中的小写 HMAC
     * @param body            收到的原始 HTTP body
     * @param nowMillis       注入的当前 Unix 毫秒时间，用于可测试的时钟门禁
     * @return replay claim 的过期时刻；调用方必须用 producer 与 nonce 原子占用共享存储
     */
    public long verify(
            String producer,
            String path,
            long timestampMillis,
            String nonce,
            String signature,
            byte[] body,
            long nowMillis) {
        validateSigningFields(producer, path, timestampMillis, nonce, body);
        if (nowMillis < 0 || distance(timestampMillis, nowMillis) > maxClockSkewMillis) {
            throw new SagaProtocolException("Saga HTTP timestamp is outside the accepted window");
        }
        if (!isLowerHex(signature, 64)) {
            throw new SagaProtocolException("Saga HTTP signature is not lowercase HMAC hex");
        }
        byte[] actual = mac(producer, path, timestampMillis, nonce, body);
        byte[] expected = HexFormat.of().parseHex(signature);
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new SagaProtocolException("Saga HTTP signature does not match");
        }
        return timestampMillis > Long.MAX_VALUE - maxClockSkewMillis
                ? Long.MAX_VALUE
                : timestampMillis + maxClockSkewMillis;
    }

    /**
     * 业务作用：为每次 HTTP 尝试生成 Rust replay claim 所需的 128 位随机 nonce。
     *
     * @return 32 个小写十六进制字符
     */
    public static String newNonce() {
        byte[] bytes = new byte[16];
        synchronized (RANDOM) {
            RANDOM.nextBytes(bytes);
        }
        return HEX.formatHex(bytes);
    }

    /**
     * 业务作用：返回本验签器接受的时钟窗口，供本地配置门禁和观测使用。
     *
     * @return 最大允许的毫秒偏差
     */
    public long maxClockSkewMillis() {
        return maxClockSkewMillis;
    }

    /**
     * 业务作用：按 Rust 相同的字段顺序和分隔规则绑定身份、目标与原始正文。
     *
     * @param producer        凭据绑定的逻辑主体
     * @param path            listener 实际路径
     * @param timestampMillis 本次尝试的 Unix 毫秒时间
     * @param nonce           本次尝试的一次性身份
     * @param body            原始请求字节
     * @return HMAC-SHA-256 字节；算法不可用时拒绝生成签名。
     */
    private byte[] mac(String producer, String path, long timestampMillis, String nonce, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            mac.update(MAC_PREFIX.getBytes(StandardCharsets.US_ASCII));
            mac.update(producer.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(path.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(Long.toString(timestampMillis).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '\n');
            mac.update(nonce.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '\n');
            mac.update(body);
            return mac.doFinal();
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    /**
     * 业务作用：签名前拒绝有歧义的身份、路径、时间和 nonce，防止两端认证范围不同。
     *
     * @param producer        逻辑主体
     * @param path            实际 listener 路径
     * @param timestampMillis 非负 Unix 毫秒时间
     * @param nonce           规范的小写十六进制随机身份
     * @param body            完整原始正文
     */
    private static void validateSigningFields(
            String producer,
            String path,
            long timestampMillis,
            String nonce,
            byte[] body) {
        SagaIds.requireStructured(producer, "saga_producer");
        if (path == null || path.isEmpty() || path.charAt(0) != '/' || path.indexOf('?') >= 0
                || path.indexOf('#') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(path)) {
            throw new SagaProtocolException("Saga HTTP path is not a valid listener path");
        }
        if (timestampMillis < 0) {
            throw new SagaProtocolException("Saga HTTP timestamp must not be negative");
        }
        if (!isLowerHex(nonce, 32)) {
            throw new SagaProtocolException("Saga HTTP nonce is not lowercase 128-bit hex");
        }
        Objects.requireNonNull(body, "body");
    }

    /**
     * 业务作用：对已校验的非负时间计算绝对偏差，用于限制签名重放窗口。
     *
     * @param left  第一个 Unix 毫秒时间
     * @param right 第二个 Unix 毫秒时间
     * @return 两个非负时间的绝对差值。
     */
    private static long distance(long left, long right) {
        return left >= right ? left - right : right - left;
    }

    /**
     * 业务作用：限定签名材料的唯一文本形式，拒绝大小写或长度漂移。
     *
     * @param value  待校验文本
     * @param length 协议固定长度
     * @return 精确长度的小写十六进制文本才成立。
     */
    private static boolean isLowerHex(String value, int length) {
        return value != null && value.length() == length
                && value.chars().allMatch(character -> character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f');
    }

    /**
     * 业务作用：拒绝非规范 HMAC 配置，确保与 Rust 使用相同的 32 字节材料。
     *
     * @param keyHex 64 个小写十六进制字符
     * @return 解码后的密钥；格式错误时拒绝构造认证器。
     */
    private static byte[] decodeKey(String keyHex) {
        if (!isLowerHex(keyHex, 64)) {
            throw new IllegalArgumentException("Saga HTTP key must be 64 lowercase hexadecimal characters");
        }
        return HexFormat.of().parseHex(keyHex);
    }

    /**
     * 业务作用：将 MAC 编码为协议规定的小写表示，保持签名比较一致。
     *
     * @param value MAC 字节
     * @return 小写十六进制签名。
     */
    private static String hex(byte[] value) {
        return HEX.formatHex(value);
    }
}
