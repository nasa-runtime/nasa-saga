package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaCommandEnvelope;
import io.github.nasaruntime.saga.rc.SagaDeliveryReceipt;
import io.github.nasaruntime.saga.rc.SagaHttpInboundRequest;
import io.github.nasaruntime.saga.rc.SagaHttpParticipantResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Rust managed HTTP `/commands` 的 Java participant 入站适配器。
 *
 * <p>本类只负责 HTTP 认证、共享 replay claim、envelope 合同和 transport receipt；Inbox、gate、业务事实
 * 与 result Outbox 必须由 handler 在同一业务事务中完成。</p>
 */
public final class SagaHttpCommandIngress {

    private static final byte[] COMMITTED = statusBody("Committed");
    private static final byte[] DUPLICATE = statusBody("Duplicate");
    private static final byte[] DETERMINISTIC_REJECT = statusBody("DeterministicReject");

    private final String expectedPath;
    private final String expectedProducer;
    private final SagaHttpMessageAuthenticator authenticator;
    private final SagaHttpReplayClaimStore replayClaimStore;
    private final SagaCommandHandler handler;
    private final int bodyLimitBytes;

    /**
     * 业务作用：把一个 Java participant handler 绑定到固定 Rust command 路由和共享 replay 权威。
     *
     * @param expectedPath     宿主实际暴露的完整 command path
     * @param expectedProducer Rust Orchestrator 的逻辑 producer
     * @param authenticator    与 Rust command credential 对应的 HMAC 验证器
     * @param replayClaimStore 多副本共享的 nonce 原子占用实现
     * @param handler          本地事务 handler
     * @param bodyLimitBytes   command body 最大字节数
     */
    public SagaHttpCommandIngress(
            String expectedPath,
            String expectedProducer,
            SagaHttpMessageAuthenticator authenticator,
            SagaHttpReplayClaimStore replayClaimStore,
            SagaCommandHandler handler,
            int bodyLimitBytes) {
        if (expectedPath == null || expectedPath.isEmpty() || !expectedPath.startsWith("/")
                || expectedPath.indexOf('?') >= 0 || expectedPath.indexOf('#') >= 0) {
            throw new IllegalArgumentException("expectedPath must be an absolute path");
        }
        this.expectedPath = expectedPath;
        this.expectedProducer = SagaIds.requireStructured(expectedProducer, "expected_producer");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.replayClaimStore = Objects.requireNonNull(replayClaimStore, "replayClaimStore");
        this.handler = Objects.requireNonNull(handler, "handler");
        if (bodyLimitBytes <= 0) {
            throw new IllegalArgumentException("bodyLimitBytes must be positive");
        }
        this.bodyLimitBytes = bodyLimitBytes;
    }

    /**
     * 业务作用：在本地事务提交前不返回 Committed，并按 Rust HTTP 合同把重复、确定拒绝和暂时故障分开。
     *
     * @param request 宿主 Web 框架提供的原始 command 请求
     * @return 异步 participant HTTP 响应
     */
    public CompletionStage<SagaHttpParticipantResponse> handle(SagaHttpInboundRequest request) {
        Objects.requireNonNull(request, "request");
        if (!"POST".equals(request.method()) || !expectedPath.equals(request.path())) {
            return completed(response(404, new byte[0]));
        }
        byte[] body = request.body();
        if (body.length > bodyLimitBytes) {
            return completed(response(413, new byte[0]));
        }
        String contentType = request.header("content-type");
        if (!isJsonContentType(contentType)) {
            return completed(response(415, DETERMINISTIC_REJECT));
        }
        String producer = request.header(SagaHttpTransport.PRODUCER_HEADER);
        String nonce = request.header(SagaHttpTransport.NONCE_HEADER);
        String signature = request.header(SagaHttpTransport.SIGNATURE_HEADER);
        long timestamp;
        try {
            timestamp = Long.parseLong(Objects.requireNonNull(request.header(SagaHttpTransport.TIMESTAMP_HEADER)));
        } catch (RuntimeException exception) {
            return completed(response(401, new byte[0]));
        }
        if (!expectedProducer.equals(producer)) {
            return completed(response(403, DETERMINISTIC_REJECT));
        }

        long expiresAt;
        try {
            expiresAt = authenticator.verify(
                    producer, request.path(), timestamp, nonce, signature, body, System.currentTimeMillis());
        } catch (SagaProtocolException exception) {
            return completed(response(401, new byte[0]));
        }
        try {
            if (!replayClaimStore.claim(producer, nonce, expiresAt)) {
                return completed(response(409, new byte[0]));
            }
        } catch (Exception exception) {
            return completed(response(503, new byte[0]));
        }

        SagaCommandEnvelope command;
        try {
            command = SagaCommandEnvelope.decode(body);
            if (!command.commandId().equals(request.header(SagaHttpTransport.EVENT_ID_HEADER))) {
                return completed(response(422, DETERMINISTIC_REJECT));
            }
        } catch (SagaProtocolException exception) {
            return completed(response(422, DETERMINISTIC_REJECT));
        }

        CompletionStage<SagaDeliveryReceipt> completion;
        try {
            completion = handler.handle(command, producer, SagaTraceparent.validOrNull(request.header("traceparent")));
        } catch (RuntimeException exception) {
            return completed(classify(exception));
        }
        if (completion == null) {
            return completed(response(503, new byte[0]));
        }
        return completion.handle((receipt, failure) -> {
            if (failure != null) {
                return classify(unwrap(failure));
            }
            if (receipt == null) {
                return response(503, new byte[0]);
            }
            return switch (receipt.kind()) {
                case COMMITTED -> response(200, COMMITTED);
                case DUPLICATE -> response(200, DUPLICATE);
                case DETERMINISTIC_REJECT -> response(422, DETERMINISTIC_REJECT);
                default -> response(503, new byte[0]);
            };
        });
    }

    /**
     * 业务作用：区分确定的协议拒绝与尚无提交确认的处理失败。
     *
     * @param failure 已展开的处理异常
     * @return 协议错误为 422，其它失败为可重试 503。
     */
    private static SagaHttpParticipantResponse classify(Throwable failure) {
        return failure instanceof SagaProtocolException
                ? response(422, DETERMINISTIC_REJECT)
                : response(503, new byte[0]);
    }

    /**
     * 业务作用：识别异步包装内的业务异常，避免将明确协议拒绝误判为瞬态故障。
     *
     * @param failure 回调收到的异常
     * @return 有原因的异步包装返回原因，其它异常保持原值。
     */
    private static Throwable unwrap(Throwable failure) {
        if ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    /**
     * 业务作用：构造最小协议响应，空正文不声明 JSON 媒体。
     *
     * @param statusCode 已裁决的 HTTP 状态
     * @param body       已编码的固定收据正文
     * @return 只包含必要头部与正文的参与方响应。
     */
    private static SagaHttpParticipantResponse response(int statusCode, byte[] body) {
        return new SagaHttpParticipantResponse(
                statusCode,
                body.length == 0 ? Map.of() : Map.of("content-type", SagaHttpTransport.CONTENT_TYPE),
                body);
    }

    /**
     * 业务作用：编码框架固定收据，不把异常内容或业务正文回显给调用方。
     *
     * @param status 框架内固定的收据字面值
     * @return UTF-8 收据正文。
     */
    private static byte[] statusBody(String status) {
        return ("{\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 业务作用：在协议解码前确认请求使用 JSON 媒体，允许独立的媒体参数。
     *
     * @param value Content-Type 头
     * @return application/json 媒体成立，缺失或其它媒体拒绝。
     */
    private static boolean isJsonContentType(String value) {
        if (value == null) {
            return false;
        }
        String mediaType = value.split(";", 2)[0].trim();
        return "application/json".equalsIgnoreCase(mediaType);
    }

    /**
     * 业务作用：把已完成的准入裁决交给异步入口，不调度业务处理。
     *
     * @param value 已确定的响应
     * @param <T>   响应类型
     * @return 已完成且携带原响应的阶段。
     */
    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
