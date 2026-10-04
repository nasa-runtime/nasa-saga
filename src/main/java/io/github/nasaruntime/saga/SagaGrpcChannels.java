package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaExecutionConfig;
import io.github.nasaruntime.saga.rc.SagaMtlsConfig;
import io.github.nasaruntime.saga.rc.SagaMtlsMaterial;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 构造满足 Rust Saga gRPC mTLS 合同的客户端 channel。
 *
 * <p>默认按需启动 nasa-core 默认时间轮，将应用回调与阻塞辅助任务交给其虚拟线程执行器；
 * {@link SagaExecutionConfig} 可关闭该接入，Netty 仍管理 I/O 线程。关闭 channel 不停止共享时间轮，
 * 宿主须在全部依赖组件结束后停止时间轮，其非守护调度线程会影响进程退出。</p>
 */
public final class SagaGrpcChannels {

    /**
     * 业务作用：限制 channel 工具为静态入口，每次构造显式绑定 mTLS 材料。
     * 参数说明：无。
     * 返回：不对外创建实例。
     */
    private SagaGrpcChannels() {}

    /**
     * 业务作用：以 secret provider 内存材料建立 mTLS 通道，默认借用并按需启动时间轮任务执行器。
     *
     * @param target   gRPC HTTPS endpoint 对应的 host:port 或 resolver target
     * @param material 与 Rust managed credential 同构的证书材料
     * @return 已绑定双向身份和服务端名称校验的 channel；材料非法时拒绝。
     * @throws SSLException 证书、私钥或信任链不能构造
     */
    public static ManagedChannel openMtls(String target, SagaMtlsMaterial material) throws SSLException {
        return openMtls(target, material, SagaExecutionConfig.DEFAULT);
    }

    /**
     * 业务作用：以显式执行策略和内存证书构造 mTLS 通道，统一应用回调与阻塞辅助任务的执行资源。
     *
     * @param target gRPC HTTPS endpoint 对应的 host:port 或 resolver target
     * @param material 与 Rust managed credential 同构的证书材料
     * @param executionConfig 默认启用时间轮执行器；关闭时保留 gRPC 默认执行器
     * @return 已绑定双向身份与执行策略的 channel；关闭 channel 不关闭共享执行器
     * @throws SSLException 证书、私钥或信任链不能构造
     */
    public static ManagedChannel openMtls(String target, SagaMtlsMaterial material,
                                        SagaExecutionConfig executionConfig) throws SSLException {
        Objects.requireNonNull(executionConfig, "executionConfig");
        Objects.requireNonNull(material, "material");
        var context = GrpcSslContexts.forClient()
                .trustManager(new ByteArrayInputStream(material.caCertificatePem().getBytes(StandardCharsets.UTF_8)))
                .keyManager(new ByteArrayInputStream(material.identityCertificatePem().getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayInputStream(material.identityPrivateKeyPem().getBytes(StandardCharsets.UTF_8)))
                .build();
        var builder = NettyChannelBuilder.forTarget(target).sslContext(context);
        if (material.domainName() != null && !material.domainName().isBlank())
            builder.overrideAuthority(material.domainName());
        configureExecutor(builder, executionConfig);
        return builder.build();
    }

    /**
     * 业务作用：以证书文件构造 Rust Saga mTLS channel，默认借用并按需启动时间轮任务执行器。
     *
     * @param target gRPC target，例如 dns:///saga-orchestrator.internal:39051
     * @param mtls   mTLS 证书材料
     * @return 已配置 TLS 的 ManagedChannel
     * @throws SSLException 证书、私钥或 TLS context 无法构造
     */
    public static ManagedChannel openMtls(String target, SagaMtlsConfig mtls) throws SSLException {
        return openMtls(target, mtls, SagaExecutionConfig.DEFAULT);
    }

    /**
     * 业务作用：以显式执行策略和证书文件构造 Rust Saga 控制面或 result transport 的 mTLS channel。
     *
     * @param target gRPC resolver target 或 host:port
     * @param mtls mTLS 证书文件与可选服务端名称
     * @param executionConfig 默认启用时间轮执行器；关闭时保留 gRPC 默认执行器
     * @return 绑定双向 TLS 与执行策略的 channel；关闭 channel 不关闭共享执行器
     * @throws SSLException 证书、私钥或 TLS context 无法构造
     */
    public static ManagedChannel openMtls(String target, SagaMtlsConfig mtls,
                                        SagaExecutionConfig executionConfig) throws SSLException {
        Objects.requireNonNull(executionConfig, "executionConfig");
        Objects.requireNonNull(mtls, "mtls");
        var sslContext = GrpcSslContexts.forClient()
                .trustManager(mtls.trustCertificate().toFile())
                .keyManager(mtls.clientCertificate().toFile(), mtls.clientPrivateKey().toFile())
                .build();
        var builder = NettyChannelBuilder.forTarget(target).sslContext(sslContext);
        if (mtls.authorityOverride() != null && !mtls.authorityOverride().isBlank()) {
            builder.overrideAuthority(mtls.authorityOverride());
        }
        configureExecutor(builder, executionConfig);
        return builder.build();
    }

    /**
     * 业务作用：让 channel 回调和 DNS 等阻塞辅助任务使用同一执行策略，保留 Netty 自身的 I/O 调度。
     * 返回：启用时绑定共享执行器；关闭时保留 builder 的默认选择，启动失败向调用方传播。
     *
     * @param builder 尚未构建的 mTLS channel builder
     * @param executionConfig 构造期间采用的执行策略
     */
    private static void configureExecutor(NettyChannelBuilder builder, SagaExecutionConfig executionConfig) {
        Executor executor = SagaExecutors.resolve(executionConfig);
        if (executor != null) {
            builder.executor(executor).offloadExecutor(executor);
        }
    }
}
