package io.github.nasaruntime.saga.rc;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Java 到 Rust Saga gRPC 端点的 mTLS 客户端材料。
 *
 * @param trustCertificate  CA 证书 PEM
 * @param clientCertificate Java client leaf certificate PEM
 * @param clientPrivateKey  Java client private key PEM
 * @param authorityOverride 可选的 TLS authority/name override
 */
public record SagaMtlsConfig(
        Path trustCertificate,
        Path clientCertificate,
        Path clientPrivateKey,
        String authorityOverride) {

    /**
     * 业务作用：拒绝不完整的 mTLS 材料，防止 Java client 退化为明文或匿名连接。
     *
     * @param trustCertificate  CA 证书 PEM
     * @param clientCertificate client certificate PEM
     * @param clientPrivateKey  client private key PEM
     * @param authorityOverride 可选的 authority
     */
    public SagaMtlsConfig {
        Objects.requireNonNull(trustCertificate, "trustCertificate");
        Objects.requireNonNull(clientCertificate, "clientCertificate");
        Objects.requireNonNull(clientPrivateKey, "clientPrivateKey");
    }
}
