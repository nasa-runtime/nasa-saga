package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaCommandEnvelope;
import io.github.nasaruntime.saga.rc.SagaDeliveryReceipt;
import io.github.nasaruntime.saga.rc.SagaResultEnvelope;

import com.google.protobuf.ByteString;
import io.github.nasaruntime.saga.proto.transport.v1.SagaCommandTransportGrpc;
import io.github.nasaruntime.saga.proto.transport.v1.SagaDeliveryRequest;
import io.github.nasaruntime.saga.proto.transport.v1.SagaResultTransportGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Java participant 向 Rust participant/Orchestrator 投递 command/result 的 gRPC client。
 */
public final class SagaTransportClient implements AutoCloseable {

    private static final Metadata.Key<String> TRACEPARENT =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;
    private final SagaCommandTransportGrpc.SagaCommandTransportBlockingStub commandStub;
    private final SagaResultTransportGrpc.SagaResultTransportBlockingStub resultStub;
    private final Duration requestTimeout;

    /**
     * 业务作用：绑定同一 mTLS channel 上的 command/result generated client 和有界 deadline。
     *
     * @param channel        已完成 TLS 与 endpoint 校验的 channel
     * @param requestTimeout 每次 unary 投递的最大等待时间
     */
    public SagaTransportClient(ManagedChannel channel, Duration requestTimeout) {
        if (channel == null || requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("channel and positive requestTimeout are required");
        }
        this.channel = channel;
        this.commandStub = SagaCommandTransportGrpc.newBlockingStub(channel);
        this.resultStub = SagaResultTransportGrpc.newBlockingStub(channel);
        this.requestTimeout = requestTimeout;
    }

    /**
     * 业务作用：向 participant 投递已验证 command，供自定义多语言数据面复用同一收据合同。
     *
     * @param command     command envelope
     * @param traceparent 可选的 W3C traceparent
     * @return Rust participant 返回的封闭收据；远端收据损坏抛出 DATA_LOSS，不能确认或隔离原事件。
     */
    public SagaDeliveryReceipt deliverCommand(SagaCommandEnvelope command, String traceparent) {
        var response = withTraceparent(commandStub, traceparent).deliver(request(command.encode()));
        return SagaGrpcResponse.decode(() -> SagaDeliveryReceipt.fromProto(response));
    }

    /**
     * 业务作用：将 Java participant 本地事务提交后的 result 投递到 Rust Orchestrator。
     *
     * @param result      result envelope；其 event_id 必须由 command_id 派生
     * @param traceparent 可选的 W3C traceparent
     * @return Rust Orchestrator 返回的封闭收据；远端收据损坏抛出 DATA_LOSS，保留原事件等待确认。
     */
    public SagaDeliveryReceipt deliverResult(SagaResultEnvelope result, String traceparent) {
        var response = withTraceparent(resultStub, traceparent).deliver(request(result.encode()));
        return SagaGrpcResponse.decode(() -> SagaDeliveryReceipt.fromProto(response));
    }

    /**
     * 业务作用：关闭 Java participant 持有的 gRPC channel，结束后续 result 重投任务。
     */
    @Override
    public void close() {
        channel.shutdown();
    }

    /**
     * 业务作用：把已经过 envelope 校验的 UTF-8 JSON 放入 Rust transport 的 protobuf 外壳。
     *
     * @param envelope envelope JSON 字节
     * @return transport 请求
     */
    private static SagaDeliveryRequest request(byte[] envelope) {
        return SagaDeliveryRequest.newBuilder()
                .setEnvelopeJson(ByteString.copyFrom(envelope))
                .build();
    }

    /**
     * 业务作用：为 command/result 投递同时设置 unary deadline 和可选 trace metadata。
     *
     * @param stub        generated blocking stub
     * @param traceparent 可选的 W3C traceparent
     * @param <T>         generated stub 类型
     * @return 带投递约束的 stub
     */
    private <T extends AbstractStub<T>> T withTraceparent(T stub, String traceparent) {
        T deadlineStub = stub.withDeadlineAfter(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        String validTraceparent = SagaTraceparent.validOrNull(traceparent);
        if (validTraceparent == null) {
            return deadlineStub;
        }
        var metadata = new Metadata();
        metadata.put(TRACEPARENT, validTraceparent);
        return deadlineStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
}
