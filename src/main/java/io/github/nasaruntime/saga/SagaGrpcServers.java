package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaExecutionConfig;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 构造 Java participant 的 mTLS gRPC server builder。
 *
 * <p>默认按需启动 nasa-core 默认时间轮并借用其虚拟线程执行器处理服务回调；
 * {@link SagaExecutionConfig} 可关闭该接入。构造 builder 不打开监听端口，Netty 仍管理 I/O 线程。
 * 关闭 server 不停止共享时间轮；宿主须在全部依赖组件结束后停止时间轮，其非守护调度线程会影响进程退出。</p>
 */
public final class SagaGrpcServers {

    /**
     * 业务作用：限制 mTLS server 构造器为静态入口，身份材料由调用方显式提供。
     * 参数说明：无。
     * 返回：不对外创建实例。
     */
    private SagaGrpcServers() {}

    /**
     * 业务作用：创建强制 client certificate 的 participant server builder，默认按需启动并借用时间轮任务执行器。
     *
     * @param port                   participant gRPC 监听端口
     * @param clientTrustCertificate 用于验证 Rust Orchestrator client certificate 的 CA PEM
     * @param serverCertificate      participant server certificate PEM
     * @param serverPrivateKey       participant server private key PEM
     * @return 已配置双向 TLS 的 Netty server builder
     * @throws SSLException 证书、私钥或 TLS context 无法构造
     */
    public static NettyServerBuilder participantBuilder(
            int port,
            Path clientTrustCertificate,
            Path serverCertificate,
            Path serverPrivateKey) throws SSLException {
        return participantBuilder(port, clientTrustCertificate, serverCertificate, serverPrivateKey,
                SagaExecutionConfig.DEFAULT);
    }

    /**
     * 业务作用：按显式执行策略构造 participant server builder，保留 client certificate 强制校验。
     *
     * @param port participant gRPC 监听端口
     * @param clientTrustCertificate Rust command client 的信任 CA 文件
     * @param serverCertificate 本端 server certificate 文件
     * @param serverPrivateKey 本端 server private key 文件
     * @param executionConfig 默认启用时间轮执行器；关闭时保留 gRPC 默认执行器
     * @return 尚未监听端口的 mTLS builder；关闭 server 不关闭共享执行器
     * @throws SSLException 证书、私钥或 TLS context 不能构造
     */
    public static NettyServerBuilder participantBuilder(
            int port, Path clientTrustCertificate, Path serverCertificate, Path serverPrivateKey,
            SagaExecutionConfig executionConfig) throws SSLException {
        return participantBuilder(new InetSocketAddress(port), clientTrustCertificate, serverCertificate,
                serverPrivateKey, executionConfig);
    }

    /**
     * 业务作用：把双向 TLS participant listener 限定到部署声明的接口，默认按需启动并借用时间轮任务执行器。
     *
     * @param address                实际监听地址
     * @param clientTrustCertificate Rust command client 的信任 CA 文件
     * @param serverCertificate      本端 server certificate 文件
     * @param serverPrivateKey       本端 server private key 文件
     * @return 强制验证 client certificate 的 builder；材料非法时拒绝。
     * @throws SSLException TLS context 不能构造
     */
    public static NettyServerBuilder participantBuilder(InetSocketAddress address,
                                                        Path clientTrustCertificate, Path serverCertificate, Path serverPrivateKey) throws SSLException {
        return participantBuilder(address, clientTrustCertificate, serverCertificate, serverPrivateKey,
                SagaExecutionConfig.DEFAULT);
    }

    /**
     * 业务作用：按显式执行策略构造限定监听地址的 participant mTLS builder，保留宿主对实际启动的控制。
     *
     * @param address 部署允许的监听接口与端口
     * @param clientTrustCertificate Rust command client 的信任 CA 文件
     * @param serverCertificate 本端 server certificate 文件
     * @param serverPrivateKey 本端 server private key 文件
     * @param executionConfig 默认启用时间轮执行器；关闭时保留 gRPC 默认执行器
     * @return 尚未启动的 mTLS builder；共享执行器只供服务回调使用，Netty 保留 I/O 线程
     * @throws SSLException 证书、私钥或 TLS context 不能构造
     */
    public static NettyServerBuilder participantBuilder(InetSocketAddress address,
                                                        Path clientTrustCertificate, Path serverCertificate,
                                                        Path serverPrivateKey, SagaExecutionConfig executionConfig) throws SSLException {
        Objects.requireNonNull(executionConfig, "executionConfig");
        var builder = NettyServerBuilder.forAddress(address)
                .sslContext(GrpcSslContexts.forServer(serverCertificate.toFile(), serverPrivateKey.toFile())
                        .trustManager(clientTrustCertificate.toFile())
                        .clientAuth(ClientAuth.REQUIRE)
                        .build());
        Executor executor = SagaExecutors.resolve(executionConfig);
        if (executor != null) {
            builder.executor(executor);
        }
        return builder;
    }
}
