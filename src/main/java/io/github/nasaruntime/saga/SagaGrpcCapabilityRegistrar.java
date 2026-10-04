package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaCapabilityDescriptor;
import io.github.nasaruntime.saga.rc.SagaCapabilityReceipt;

import com.google.protobuf.ByteString;
import io.github.nasaruntime.saga.proto.orchestrator.v1.DefinitionKey;
import io.github.nasaruntime.saga.proto.orchestrator.v1.RegisterCapabilityRequest;
import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaDefinitionRegistryGrpc;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static io.github.nasaruntime.saga.proto.orchestrator.v1.CapabilityDescriptor.newBuilder;

/**
 * 使用显式 mTLS registry 身份登记能力；不持有 definition 发布或全局管理权威。
 */
public final class SagaGrpcCapabilityRegistrar implements SagaCapabilityRegistrar {

    private static final Metadata.Key<String> TRACE = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    private final SagaDefinitionRegistryGrpc.SagaDefinitionRegistryBlockingStub stub;
    private final long timeoutNanos;

    /**
     * 业务作用：绑定宿主持有的 mTLS channel 和单次登记预算，不接管共享 channel 生命周期。
     *
     * @param channel 已绑定受信 CA、client certificate 和服务端名称的 channel
     * @param timeout 每次登记的正时间预算
     *                返回：有界 registry 适配器；空 channel 或非正预算拒绝。
     */
    public SagaGrpcCapabilityRegistrar(Channel channel, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("positive registry timeout required");
        stub = SagaDefinitionRegistryGrpc.newBlockingStub(Objects.requireNonNull(channel, "channel"));
        timeoutNanos = timeout.toNanos();
    }

    /**
     * 业务作用：提交冻结的 canonical capability 文档，仅在收据身份、摘要和租期同时成立后交还准入证据。
     *
     * @param descriptor  当前副本完整能力合同
     * @param traceparent 可选链路上下文
     * @return 有效租约收据；gRPC 没有 catalog generation，保留零而不推断全局代际；响应合同损坏为 DATA_LOSS。
     */
    @Override
    public SagaCapabilityReceipt register(SagaCapabilityDescriptor descriptor, String traceparent) {
        Objects.requireNonNull(descriptor, "descriptor");
        long seconds = descriptor.requestedLeaseMs() / 1000;
        if (seconds <= 0 || seconds > 0xffff_ffffL || descriptor.requestedLeaseMs() % 1000 != 0) {
            throw new SagaProtocolException("gRPC capability lease must be a positive whole uint32 number of seconds");
        }
        byte[] body = SagaEnvelopeCodec.encode(descriptor);
        var capability = newBuilder()
                .setDefinition(DefinitionKey.newBuilder().setTenantId(descriptor.tenant()).setWorkflow(descriptor.workflow())
                        .setDefinitionVersion(descriptor.definitionVersion()))
                .setStep(descriptor.step()).setOwner(descriptor.owner()).setDescriptorFormat("nasaga-capability-json")
                .setCanonicalDocument(ByteString.copyFrom(body)).setSha256(SagaHttpStartCodec.sha256Hex(body))
                .setRequestedLeaseSeconds((int) seconds).build();
        var call = stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS);
        String trace = SagaTraceparent.validOrNull(traceparent);
        if (trace != null) {
            Metadata metadata = new Metadata();
            metadata.put(TRACE, trace);
            call = call.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        }
        var response = call.registerCapability(RegisterCapabilityRequest.newBuilder().setCapability(capability)
                .setRegistrationId(descriptor.replicaIdentity()).build());
        return SagaGrpcResponse.decode(() -> {
            // 成功 status 不是路由权威；先核对副本与合法 wire 时间，再按分配后的 route generation 重算摘要。
            if (!descriptor.replicaIdentity().equals(response.getRegistrationId()) || !response.hasAcceptedUntil()) {
                throw new SagaProtocolException("gRPC capability receipt identity or deadline is invalid");
            }
            long accepted = SagaGrpcResponse.timestampMillis(response.getAcceptedUntil());
            SagaCapabilityReceipt receipt = new SagaCapabilityReceipt(accepted, response.getCapabilityDigest(),
                    response.getRouteGeneration(), 0);
            receipt.validateFor(descriptor, System.currentTimeMillis());
            return receipt;
        });
    }
}
