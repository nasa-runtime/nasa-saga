package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaMtlsPrincipalInterceptor;
import io.github.nasaruntime.saga.SagaProtocolException;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Rust managed gRPC credential JSON 的内存投影；调用者只从 secret provider 读取，不写入日志或 capability。
 *
 * @param caCertificatePem       出站服务端信任 CA
 * @param identityCertificatePem 本端 client leaf certificate 及可选链
 * @param identityPrivateKeyPem  本端 client 私钥
 * @param domainName             显式服务端证书名称；为空时使用 endpoint host
 */
public record SagaMtlsMaterial(
        @JsonProperty("ca_certificate_pem") String caCertificatePem,
        @JsonProperty("identity_certificate_pem") String identityCertificatePem,
        @JsonProperty("identity_private_key_pem") String identityPrivateKeyPem,
        @JsonProperty("domain_name") String domainName) {

    /**
     * 业务作用：拒绝不完整 mTLS 材料，避免协议选择后静默退化为匿名或明文。
     * 参数说明：CA、client certificate 和 private key 是必需 PEM；domainName 是可选证书名称。
     * 返回：完整材料快照；证书链与密钥匹配由 TLS channel 构造继续核验。
     */
    public SagaMtlsMaterial {
        if (caCertificatePem == null || caCertificatePem.isBlank() || identityCertificatePem == null
                || identityCertificatePem.isBlank() || identityPrivateKeyPem == null || identityPrivateKeyPem.isBlank()) {
            throw new SagaProtocolException("complete gRPC mTLS material is required");
        }
    }

    /**
     * 业务作用：从实际 result client leaf certificate 派生供 Rust 信任映射使用的 principal。
     * 参数说明：无。
     *
     * @return sha256 certificate principal；PEM 无法解析时拒绝，不输出证书或私钥。
     */
    public String principal() {
        try {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(identityCertificatePem.getBytes(StandardCharsets.UTF_8)));
            return SagaMtlsPrincipalInterceptor.principalOf(certificate);
        } catch (CertificateException failure) {
            throw new SagaProtocolException("gRPC client certificate is invalid", failure);
        }
    }

    /**
     * 业务作用：阻止默认记录格式把 credential PEM 和私钥带入日志。
     * 参数说明：无。
     *
     * @return 不包含身份材料的固定描述。
     */
    @Override
    public String toString() {
        return "SagaMtlsMaterial[redacted]";
    }
}
