package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaCommandEnvelope;
import io.github.nasaruntime.saga.rc.SagaDeliveryReceipt;

import io.github.nasaruntime.saga.proto.transport.v1.SagaCommandTransportGrpc;
import io.github.nasaruntime.saga.proto.transport.v1.SagaDeliveryRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Java participant 的 command transport 入站服务。
 *
 * <p>该服务只负责认证、协议复验和把请求交给本地事务 handler，不持有 Saga 全局状态，也不执行 Rust
 * Orchestrator 的状态迁移、timer 或补偿计划。
 */
public final class SagaCommandTransportService extends SagaCommandTransportGrpc.SagaCommandTransportImplBase {

    private static final String UNAUTHORIZED_REASON = "saga_command_producer_unauthorized";
    private static final String PAYLOAD_INVALID_REASON = "saga_command_payload_undecodable";
    private static final String CONTRACT_INVALID_REASON = "saga_command_contract_invalid";

    private final String expectedProducer;
    private final SagaCommandHandler handler;

    /**
     * 业务作用：把一个 Java participant handler 绑定到单一受信 Rust Orchestrator identity。
     *
     * @param expectedProducer 允许投递 command 的逻辑 producer
     * @param handler 本地 Inbox、gate、业务事实和 result Outbox 的事务适配器
     */
    public SagaCommandTransportService(String expectedProducer, SagaCommandHandler handler) {
        this.expectedProducer = SagaIds.requireStructured(expectedProducer, "expected_producer");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * 业务作用：在本地事务完成前不返回提交收据，并把确定性合同错误与可重试故障分开编码。
     *
     * @param request command JSON transport 请求
     * @param responseObserver gRPC unary 响应观察者
     */
    @Override
    public void deliver(
            SagaDeliveryRequest request,
            StreamObserver<io.github.nasaruntime.saga.proto.transport.v1.SagaDeliveryReceipt> responseObserver) {
        String producer = SagaMtlsPrincipalInterceptor.currentProducer();
        if (producer == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("SagaMtlsPrincipalInterceptor is required")
                    .asRuntimeException());
            return;
        }
        if (!expectedProducer.equals(producer)) {
            respond(responseObserver, receipt(SagaReceiptKind.DETERMINISTIC_REJECT, UNAUTHORIZED_REASON));
            return;
        }

        SagaCommandEnvelope command;
        try {
            command = SagaEnvelopeCodec.decodeStrict(
                    request.getEnvelopeJson().toByteArray(), SagaCommandEnvelope.class);
        } catch (SagaProtocolException exception) {
            respond(responseObserver, receipt(SagaReceiptKind.DETERMINISTIC_REJECT, PAYLOAD_INVALID_REASON));
            return;
        }
        try {
            command.validate();
        } catch (SagaProtocolException exception) {
            respond(responseObserver, receipt(SagaReceiptKind.DETERMINISTIC_REJECT, CONTRACT_INVALID_REASON));
            return;
        }

        CompletionStage<SagaDeliveryReceipt> completion;
        try {
            completion = handler.handle(command, producer, SagaMtlsPrincipalInterceptor.currentTraceparent());
        } catch (RuntimeException exception) {
            respond(responseObserver, classify(exception));
            return;
        }
        if (completion == null) {
            respond(responseObserver, receipt(SagaReceiptKind.RETRYABLE, ""));
            return;
        }
        completion.whenComplete((deliveryReceipt, failure) -> {
            if (failure != null) {
                respond(responseObserver, classify(unwrap(failure)));
            } else if (deliveryReceipt == null) {
                respond(responseObserver, receipt(SagaReceiptKind.RETRYABLE, ""));
            } else {
                respond(responseObserver, deliveryReceipt);
            }
        });
    }

    /**
     * 业务作用：把 handler 异常收窄为 Rust 能执行的确定性拒绝或可重试收据。
     *
     * @param failure handler 抛出的异常
     * @return 封闭的 Java delivery receipt
     */
    private static SagaDeliveryReceipt classify(Throwable failure) {
        return failure instanceof SagaProtocolException
                ? receipt(SagaReceiptKind.DETERMINISTIC_REJECT, CONTRACT_INVALID_REASON)
                : receipt(SagaReceiptKind.RETRYABLE, "");
    }

    /**
     * 业务作用：去除 CompletionStage 包装，使本地合同异常能进入确定性分类。
     *
     * @param failure 异步完成失败值
     * @return 最内层可分类异常
     */
    private static Throwable unwrap(Throwable failure) {
        if ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    /**
     * 业务作用：集中构造 transport 收据，保证入站服务只返回协议枚举和低基数原因码。
     *
     * @param kind 收据类型
     * @param reason 原因码
     * @return Java delivery receipt
     */
    private static SagaDeliveryReceipt receipt(SagaReceiptKind kind, String reason) {
        return new SagaDeliveryReceipt(kind, reason);
    }

    /**
     * 业务作用：在收据已经确定后完成 unary response，通知 Rust 是否可以前移 Outbox。
     *
     * @param responseObserver gRPC 响应观察者
     * @param receipt 已确定的领域收据
     */
    private static void respond(
            StreamObserver<io.github.nasaruntime.saga.proto.transport.v1.SagaDeliveryReceipt> responseObserver,
            SagaDeliveryReceipt receipt) {
        responseObserver.onNext(receipt.toProto());
        responseObserver.onCompleted();
    }
}
