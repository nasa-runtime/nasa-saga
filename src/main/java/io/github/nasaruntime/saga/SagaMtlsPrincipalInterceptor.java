package io.github.nasaruntime.saga;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Iterator;

/**
 * 从 gRPC TLS session 建立受信的逻辑 producer identity，并拒绝匿名或未登记证书。
 */
public final class SagaMtlsPrincipalInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> TRACEPARENT =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    private static final Context.Key<String> PRODUCER = Context.key("nasa-saga-producer");
    private static final Context.Key<String> TRACE = Context.key("nasa-saga-traceparent");
    private static final HexFormat HEX = HexFormat.of();

    private final Map<String, String> principalToProducer;

    /**
     * 业务作用：冻结 leaf certificate principal 到逻辑 Rust Orchestrator identity 的授权映射。
     *
     * @param principalToProducer mTLS principal 到逻辑 producer 的启动期映射
     */
    public SagaMtlsPrincipalInterceptor(Map<String, String> principalToProducer) {
        if (principalToProducer == null || principalToProducer.isEmpty()) {
            throw new IllegalArgumentException("at least one trusted mTLS principal is required");
        }
        principalToProducer.forEach((principal, producer) -> {
            requirePrincipal(principal);
            SagaIds.requireStructured(producer, "producer");
        });
        this.principalToProducer = Map.copyOf(principalToProducer);
    }

    /**
     * 业务作用：在进入 generated service 前验证 TLS peer，并把可信 producer 与 trace 绑定到 gRPC Context。
     *
     * @param call    当前 gRPC 调用
     * @param headers 入站 metadata
     * @param next    后续服务处理器
     * @param <ReqT>  请求类型
     * @param <RespT> 响应类型
     * @return 已附加可信 Context 的监听器；身份失败时返回空监听器
     */
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        String producer = resolveProducer(call);
        if (producer == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("trusted mTLS principal is required"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        String traceparent = traceparent(headers);
        Context context = Context.current().withValue(PRODUCER, producer);
        if (traceparent != null) {
            context = context.withValue(TRACE, traceparent);
        }
        return Contexts.interceptCall(context, call, headers, next);
    }

    /**
     * 业务作用：读取当前请求经过证书映射后的逻辑 producer，供 participant service 做路由授权。
     *
     * @return 逻辑 producer；当前线程不在受信 gRPC 请求中时为 {@code null}
     */
    public static String currentProducer() {
        return PRODUCER.get();
    }

    /**
     * 业务作用：读取当前请求携带的 traceparent，供业务日志和结果 Outbox 关联调用链。
     *
     * @return traceparent；请求没有可传播值时为 {@code null}
     */
    public static String currentTraceparent() {
        return TRACE.get();
    }

    /**
     * 业务作用：按 Rust transport 的规则从 leaf certificate DER 派生稳定 mTLS principal。
     *
     * @param certificate 已由 TLS 验证的 leaf certificate
     * @return {@code sha256:} 加 64 位小写十六进制摘要
     */
    public static String principalOf(X509Certificate certificate) {
        Objects.requireNonNull(certificate, "certificate");
        try {
            return "sha256:" + HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded()));
        } catch (Exception exception) {
            throw new SagaProtocolException("mTLS peer principal cannot be derived", exception);
        }
    }

    /**
     * 业务作用：按 Rust transport 的单值规则读取并校验 traceparent，重复或非法 header 不进入业务因果链。
     *
     * @param headers 当前 gRPC metadata
     * @return 唯一且合法的 traceparent；缺失、重复或非法时为 {@code null}
     */
    private static String traceparent(Metadata headers) {
        Iterable<String> allValues = headers.getAll(TRACEPARENT);
        if (allValues == null) {
            return null;
        }
        Iterator<String> values = allValues.iterator();
        if (!values.hasNext()) {
            return null;
        }
        String value = values.next();
        if (values.hasNext()) {
            return null;
        }
        return SagaTraceparent.validOrNull(value);
    }

    /**
     * 业务作用：从 TLS session 的 leaf certificate 查找启动期冻结的逻辑 producer 映射。
     *
     * @param call 当前 gRPC 调用
     * @return 受信 producer；TLS 缺失、peer 未验证或未登记时为 {@code null}
     */
    private String resolveProducer(ServerCall<?, ?> call) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session == null) {
            return null;
        }
        try {
            Certificate[] certificates = session.getPeerCertificates();
            if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate certificate)) {
                return null;
            }
            return principalToProducer.get(principalOf(certificate));
        } catch (SSLPeerUnverifiedException exception) {
            return null;
        }
    }

    /**
     * 业务作用：校验授权配置中的 certificate principal 形态，避免映射键出现大小写或长度歧义。
     *
     * @param principal 待校验 principal
     */
    private static void requirePrincipal(String principal) {
        if (principal == null || !principal.startsWith("sha256:") || principal.length() != 71
                || !principal.substring("sha256:".length()).chars().allMatch(character ->
                character >= '0' && character <= '9' || character >= 'a' && character <= 'f')) {
            throw new IllegalArgumentException("principal must be sha256 followed by lowercase certificate digest");
        }
    }
}
