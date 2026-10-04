package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaExecutionConfig;
import io.github.nasaruntime.saga.rc.SagaHttpResponse;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Rust managed HTTP 的有界签名传输层。
 *
 * <p>该类不解析业务 JSON，也不管理重试；每次调用都会生成新的 timestamp、nonce 和 signature，
 * 上层可以据此安全地重试同一业务 body。</p>
 * <p>默认按需启动 nasa-core 默认时间轮并借用其虚拟线程执行器；
 * {@link SagaExecutionConfig} 可关闭该接入。每个实例拥有独立 HttpClient，关闭时取消其未完成请求，
 * 不停止共享时间轮。宿主须在全部依赖组件结束后停止时间轮，其非守护调度线程会影响进程退出。</p>
 */
public final class SagaHttpTransport implements AutoCloseable {

    public static final String CONTENT_TYPE = "application/json";
    public static final String PRODUCER_HEADER = "x-saga-producer";
    public static final String TIMESTAMP_HEADER = "x-saga-timestamp";
    public static final String NONCE_HEADER = "x-saga-nonce";
    public static final String SIGNATURE_HEADER = "x-saga-signature";
    public static final String EVENT_ID_HEADER = "x-saga-event-id";

    private final URI baseUri;
    private final String producer;
    private final SagaHttpMessageAuthenticator authenticator;
    private final HttpClient client;
    private final Duration requestTimeout;
    private final long requestTimeoutNanos;
    private final int responseLimitBytes;

    /**
     * 业务作用：绑定 Rust Orchestrator 的通信边界，默认借用并按需启动时间轮的虚拟线程执行器。
     * 返回：拥有独立 HTTP client 的传输实例；非法地址、凭据身份或边界阻止构造。
     *
     * @param baseUri            只允许 origin 加 base path，不得包含 query、fragment 或凭据
     * @param producer           API credential 绑定的逻辑 producer
     * @param authenticator      API HMAC 验签器
     * @param requestTimeout     单次 HTTP 请求超时时间
     * @param responseLimitBytes 响应正文最大字节数
     */
    public SagaHttpTransport(
            URI baseUri,
            String producer,
            SagaHttpMessageAuthenticator authenticator,
            Duration requestTimeout,
            int responseLimitBytes) {
        this(baseUri, producer, authenticator, requestTimeout, responseLimitBytes, SagaExecutionConfig.DEFAULT);
    }

    /**
     * 业务作用：按显式执行策略构造有界 HTTP 通信入口，保持请求预算和共享执行器的资源归属。
     * 返回：拥有 HTTP client 的传输实例；启用接入时只借用共享执行器；参数非法或执行器正在关闭时拒绝构造。
     *
     * @param baseUri 只允许 origin 加 base path，不得包含 query、fragment 或凭据
     * @param producer API credential 绑定的逻辑 producer
     * @param authenticator API HMAC 验签器
     * @param requestTimeout 单次完整 HTTP 请求的超时时间
     * @param responseLimitBytes 响应正文最大字节数
     * @param executionConfig 默认启用时间轮执行器；关闭时由 HttpClient 创建默认执行器
     */
    public SagaHttpTransport(
            URI baseUri,
            String producer,
            SagaHttpMessageAuthenticator authenticator,
            Duration requestTimeout,
            int responseLimitBytes,
            SagaExecutionConfig executionConfig) {
        Objects.requireNonNull(executionConfig, "executionConfig");
        this.baseUri = validateBaseUri(baseUri);
        this.producer = SagaIds.requireStructured(producer, "saga_producer");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (responseLimitBytes <= 0) {
            throw new IllegalArgumentException("responseLimitBytes must be positive");
        }
        this.requestTimeout = requestTimeout;
        try {
            this.requestTimeoutNanos = requestTimeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("requestTimeout exceeds the monotonic budget", exception);
        }
        this.responseLimitBytes = responseLimitBytes;
        var builder = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        Executor executor = SagaExecutors.resolve(executionConfig);
        if (executor != null) {
            builder.executor(executor);
        }
        this.client = builder.build();
    }

    /**
     * 业务作用：向可靠投递层公开完整 HTTP 预算，确保发送前尚有足够 lease。
     * 参数说明：无。
     *
     * @return 本 transport 强制执行的请求超时
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * 业务作用：向 Rust listener 发送一条固定 method/path/body 的新签名尝试，并拒绝自动重定向。
     *
     * @param method      HTTP method，只允许调用方使用固定的 GET 或 POST
     * @param path        相对于 Orchestrator base path 的绝对路由，例如 `/instances`
     * @param body        原始请求正文；重试时必须复用同一字节
     * @param eventId     command/result 的事件身份；start 请求必须为空
     * @param traceparent 合法 W3C traceparent，可为空
     * @return 在同一单调预算内完整收到且通过大小门禁的响应；超时取消传输并按结果不确定处理
     */
    public SagaHttpResponse exchange(
            String method,
            String path,
            byte[] body,
            String eventId,
            String traceparent) {
        long startedNanos = System.nanoTime();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new IllegalArgumentException("only GET and POST are supported");
        }
        Objects.requireNonNull(body, "body");
        if (eventId != null) {
            SagaIds.requireUuid(eventId, "event_id");
        }
        body = body.clone();
        URI target = resolve(path);
        String actualPath = target.getRawPath();
        long timestamp = System.currentTimeMillis();
        String nonce = SagaHttpMessageAuthenticator.newNonce();
        String signature = authenticator.sign(producer, actualPath, timestamp, nonce, body);
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .header("content-type", CONTENT_TYPE)
                .header(PRODUCER_HEADER, producer)
                .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                .header(NONCE_HEADER, nonce)
                .header(SIGNATURE_HEADER, signature)
                .method(method, publisher);
        if (eventId != null) {
            builder.header(EVENT_ID_HEADER, eventId);
        }
        String validTraceparent = SagaTraceparent.validOrNull(traceparent);
        if (validTraceparent != null) {
            builder.header("traceparent", validTraceparent);
        }
        BoundedBodySubscriber subscriber = new BoundedBodySubscriber(responseLimitBytes);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(builder.build(), info -> {
                subscriber.checkDeclaredLength(info.headers().firstValueAsLong("content-length").orElse(-1L));
                return subscriber;
            });
            // future 只有在全部正文到齐后才完成；头部、首字节和后续数据不能分别获得新的超时预算。
            long remainingNanos = requestTimeoutNanos - (System.nanoTime() - startedNanos);
            if (remainingNanos <= 0) {
                throw new TimeoutException("Saga HTTP deadline elapsed");
            }
            HttpResponse<byte[]> response = pending.get(remainingNanos, TimeUnit.NANOSECONDS);
            byte[] responseBody = response.body();
            SagaHttpResponse result = new SagaHttpResponse(
                    response.statusCode(), response.headers().map(), responseBody);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SagaHttpException(
                        classify(response.statusCode()),
                        response.statusCode(),
                        responseBody,
                        "Saga HTTP request was rejected",
                        null);
            }
            return result;
        } catch (SagaHttpException exception) {
            throw exception;
        } catch (TimeoutException exception) {
            throw uncertain(-1, "Saga HTTP response exceeded the request deadline", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SagaHttpException httpException) {
                throw httpException;
            }
            throw uncertain(-1, "Saga HTTP response is unavailable", cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw uncertain(-1, "Saga HTTP request was interrupted", exception);
        } catch (RuntimeException exception) {
            throw uncertain(-1, "Saga HTTP request could not be completed", exception);
        } finally {
            // 超时、中断和正文超限都必须主动解除订阅并取消网络交换；仅让调用方停止等待会遗留传输资源。
            if (pending != null) {
                pending.cancel(true);
            }
            subscriber.cancel();
        }
    }

    /**
     * 业务作用：立即关闭拥有的 HTTP client 并取消未完成请求，共享时间轮及其执行器仍由宿主管理。
     * <p>
     * 参数说明：无。
     * 返回：不再受理新请求且不等待远端结束响应；调用方仍须按原请求身份处理未确认的远端结果。
     */
    @Override
    public void close() {
        client.shutdownNow();
    }

    /**
     * 业务作用：返回已校验的 Orchestrator base URI，供 capability 和启动门禁记录。
     * <p>
     * 参数说明：无。
     *
     * @return 当前传输固定使用的 base URI，不改变路由
     */
    public URI baseUri() {
        return baseUri;
    }

    /**
     * 业务作用：返回绑定请求签名的逻辑 producer，不暴露 HMAC key。
     * <p>
     * 参数说明：无。
     *
     * @return 当前传输固定使用的 producer identity
     */
    public String producer() {
        return producer;
    }

    /**
     * 业务作用：把固定业务路由拼接到受信 origin，避免请求参数改变目标主机或签名路由。
     *
     * @param path 相对于 base path 的绝对路由，不含 query 和 fragment
     * @return 仍指向原 origin 的请求 URI；非法路由抛出协议异常
     */
    private URI resolve(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/' || path.indexOf('?') >= 0
                || path.indexOf('#') >= 0) {
            throw new SagaProtocolException("Saga HTTP route is not a valid absolute path");
        }
        String basePath = baseUri.getRawPath();
        if (basePath == null || basePath.isEmpty()) {
            basePath = "/";
        }
        String joined = (basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath)
                + path;
        try {
            return new URI(baseUri.getScheme(), baseUri.getRawAuthority(), joined, null, null);
        } catch (Exception exception) {
            throw new SagaProtocolException("Saga HTTP route cannot be resolved", exception);
        }
    }

    /**
     * 业务作用：将出站目标限制为无凭据、无查询且路径无歧义的 HTTP origin 与 base path。
     *
     * @param value 候选 Orchestrator 地址
     * @return 通过校验的原地址；非 HTTP 地址或含歧义路径时拒绝构造
     */
    private static URI validateBaseUri(URI value) {
        if (value == null || (!"http".equalsIgnoreCase(value.getScheme())
                && !"https".equalsIgnoreCase(value.getScheme()))
                || value.getHost() == null || value.getUserInfo() != null
                || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException("Saga HTTP base URI must be an origin with an optional path");
        }
        String rawPath = value.getRawPath();
        if (rawPath != null && (rawPath.contains("//") || rawPath.contains(".."))) {
            throw new IllegalArgumentException("Saga HTTP base path contains an ambiguous segment");
        }
        return value;
    }

    /**
     * 业务作用：区分远端暂时不可用与明确拒绝，供上层保持一致的重试语义。
     *
     * @param statusCode 非成功 HTTP 状态码
     * @return 请求接收超时、过早请求、限流和服务端错误可重试，其它非成功状态作为确定拒绝
     */
    private static SagaHttpDisposition classify(int statusCode) {
        // 408 和 425 不能作为业务合同拒绝的证据；保留原身份交由 dispatcher 退避，避免永久隔离可恢复意图。
        if (statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500) {
            return SagaHttpDisposition.RETRYABLE;
        }
        return SagaHttpDisposition.DETERMINISTIC_REJECT;
    }

    /**
     * 业务作用：标记无法证明远端是否接受请求的失败，不把未知结果转成确定拒绝。
     *
     * @param statusCode 已知响应码；没有可用响应时为 -1
     * @param message    不包含请求正文和凭据的错误摘要
     * @param cause      原始失败原因，可为空
     * @return 不携带响应正文的 UNCERTAIN 异常，供调用方保留原身份重试或查询
     */
    private static SagaHttpException uncertain(int statusCode, String message, Throwable cause) {
        return new SagaHttpException(SagaHttpDisposition.UNCERTAIN, statusCode, new byte[0], message, cause);
    }

    /**
     * 有界响应订阅；只在完整正文到达后完成，不创建阻塞读取线程。
     */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        /**
         * 业务作用：为单次请求建立独立的响应大小门禁。
         *
         * @param limit 允许缓冲的正文最大字节数
         *              返回：尚未接收正文的订阅者。
         */
        private BoundedBodySubscriber(int limit) {
            this.limit = limit;
        }

        /**
         * 业务作用：在订阅正文之前拒绝已声明超限的响应。
         *
         * @param length 远端声明的正文大小；未声明时为 -1
         *               返回：超限时完成异常并禁止后续缓冲，其余响应仍需逐块校验。
         */
        private synchronized void checkDeclaredLength(long length) {
            if (length > limit) {
                onError(uncertain(-1, "Saga HTTP response exceeds the configured limit", null));
            }
        }

        /**
         * 业务作用：将完整正文作为 HTTP 请求完成的前置条件。
         * 参数说明：无。
         *
         * @return 正文完成或读取失败的阶段，不会在只收到头部时提前成功
         */
        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        /**
         * 业务作用：建立逐批背压，已经超限或取消的请求不得重新订阅。
         *
         * @param incoming JDK HTTP client 提供的正文订阅
         *                 返回：有效请求开始接收一批数据；失效或重复订阅立即取消。
         */
        @Override
        public synchronized void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null || body.isDone()) {
                incoming.cancel();
                return;
            }
            subscription = incoming;
            incoming.request(1);
        }

        /**
         * 业务作用：按实际字节数限制正文内存，不信任缺失或偏小的 content-length。
         *
         * @param buffers 当前响应批次的字节缓冲区
         *                返回：边界内缓存后请求下一批；超过边界立即取消并报告结果不确定。
         */
        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                // 拷贝之前检查上界，禁止先分配任意大小数组再报告正文超限。
                if (size > limit - output.size()) {
                    onError(uncertain(-1, "Saga HTTP response exceeds the configured limit", null));
                    return;
                }
                byte[] bytes = new byte[size];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }

        /**
         * 业务作用：终止失败响应并释放已缓存正文，避免不完整响应成为成功收据。
         *
         * @param failure 读取失败、超限或主动取消的原因
         *                返回：首次失败完成异常，后续失败不覆盖已有结果。
         */
        @Override
        public synchronized void onError(Throwable failure) {
            if (!body.isDone()) {
                body.completeExceptionally(failure);
                output = null;
                if (subscription != null) {
                    subscription.cancel();
                }
            }
        }

        /**
         * 业务作用：仅在完整响应接收结束后发布正文。
         * 参数说明：无。
         * 返回：发布有界正文并释放累积缓冲；取消后的迟到完成被忽略。
         */
        @Override
        public synchronized void onComplete() {
            if (!body.isDone()) {
                body.complete(output.toByteArray());
                output = null;
            }
        }

        /**
         * 业务作用：调用方停止等待时撤销仍在进行的正文读取。
         * 参数说明：无。
         * 返回：取消未完成订阅，不改变已经完整接收的正文。
         */
        private synchronized void cancel() {
            onError(uncertain(-1, "Saga HTTP response was cancelled", null));
        }
    }
}
