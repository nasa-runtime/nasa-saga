package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaAuditPage;
import io.github.nasaruntime.saga.rc.SagaAuditRecordView;
import io.github.nasaruntime.saga.rc.SagaAuditRequest;
import io.github.nasaruntime.saga.rc.SagaHttpResponse;
import io.github.nasaruntime.saga.rc.SagaHttpStartReceipt;
import io.github.nasaruntime.saga.rc.SagaQueryPage;
import io.github.nasaruntime.saga.rc.SagaQueryRequest;
import io.github.nasaruntime.saga.rc.SagaSnapshotView;
import io.github.nasaruntime.saga.rc.SagaStartIdentity;
import io.github.nasaruntime.saga.rc.SagaStartRequest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Rust managed HTTP control plane 的主实现。
 */
public final class HttpSagaControlPlane implements SagaControlPlane, SagaStartSender {

    private final SagaHttpTransport transport;

    /**
     * 业务作用：把已配置的 Rust API HTTP transport 提升为 start/get/query/audit 统一接口。
     *
     * @param transport 已绑定 API producer、HMAC 和 base path 的 HTTP transport
     */
    public HttpSagaControlPlane(SagaHttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * 业务作用：让 reliable dispatcher 在发送前为完整 HTTP 调用预留 lease 预算。
     * 参数说明：无。
     *
     * @return transport 的强制请求超时
     */
    public Duration requestTimeout() {
        return transport.requestTimeout();
    }

    /**
     * 业务作用：发送与 Rust `SagaApiStart` 字段完全一致的原始 JSON，等待远端持久裁决。
     *
     * @param request 固定业务身份和 definition 合同的 start 请求
     * @return 身份与本次请求一致的 Committed 或 Duplicate 收据；响应缺失、损坏或错配时为 UNCERTAIN。
     */
    @Override
    public SagaHttpStartReceipt start(SagaStartRequest request) {
        Objects.requireNonNull(request, "request");
        return sendStart(SagaHttpStartCodec.encode(request), request.traceparent(), SagaStartIdentity.from(request));
    }

    /**
     * 业务作用：发送已持久化的 raw start body，保证 reliable dispatcher 重试不重新序列化业务请求。
     *
     * @param body        已冻结的 Rust start JSON body
     * @param traceparent intent 中保存的链路上下文
     * @return 完整的 Committed 或 Duplicate 收据；dispatcher 还须按冻结 intent 复验身份，响应合同损坏时为 UNCERTAIN。
     */
    public SagaHttpStartReceipt startRaw(byte[] body, String traceparent) {
        return sendStart(body, traceparent, null);
    }

    /**
     * 业务作用：发送冻结正文，并在同一远端响应边界内构造收据及执行 direct 身份门禁。
     *
     * @param body        最终发送的原始 JSON
     * @param traceparent 可选链路上下文
     * @param expected    direct 要求的业务身份；持久化投递为空，由 dispatcher 按冻结 intent 复验
     * @return 合同完整且满足所需身份条件的收据；远端响应损坏统一为 UNCERTAIN，本地空正文仍为协议错误。
     */
    private SagaHttpStartReceipt sendStart(byte[] body, String traceparent, SagaStartIdentity expected) {
        if (body == null || body.length == 0) {
            throw new SagaProtocolException("Saga HTTP start body is required");
        }
        SagaHttpResponse response = transport.exchange("POST", "/instances", body, null, traceparent);
        return decodeJson(response, HttpStartResponse.class, decoded -> {
            SagaStartDisposition disposition = switch (decoded.status()) {
                case "Committed" -> SagaStartDisposition.COMMITTED;
                case "Duplicate" -> SagaStartDisposition.DUPLICATE;
                default -> throw new SagaProtocolException("Saga HTTP start disposition is invalid");
            };
            SagaHttpStartReceipt receipt = new SagaHttpStartReceipt(disposition, decoded.requestDigest(), decoded.saga());
            // 完整快照仍可能属于另一发起意图；必须在对外确认成功前复验身份，且不能归因为本地输入错误。
            if (expected != null && !expected.matches(receipt.saga())) {
                throw new SagaProtocolException("Saga start response identity does not match request");
            }
            return receipt;
        });
    }

    /**
     * 业务作用：按未编码的 Rust managed HTTP route 读取指定租户的实例快照。
     *
     * @param tenantId    租户身份
     * @param sagaId      Saga 实例身份
     * @param traceparent 可选链路上下文
     * @return 身份与请求一致的 Rust 快照；损坏或错配的响应为 UNCERTAIN。
     */
    @Override
    public SagaSnapshotView get(String tenantId, String sagaId, String traceparent) {
        String tenant = pathSegment(tenantId, "tenant_id");
        String saga = pathSegment(sagaId, "saga_id");
        SagaHttpResponse response = transport.exchange(
                "GET", "/instances/" + tenant + "/" + saga, new byte[0], null, traceparent);
        return decodeJson(response, SagaSnapshotView.class,
                decoded -> SagaReadResponse.get(decoded, tenantId, sagaId));
    }

    /**
     * 业务作用：把 Rust HTTP 独有的时间窗口和 keyset 条件原样交给 Orchestrator 查询。
     *
     * @param request     查询条件
     * @param traceparent 可选链路上下文
     * @return 与请求范围和页大小一致的实例页；缺失集合、损坏快照或分页合同错误为 UNCERTAIN。
     */
    @Override
    public SagaQueryPage query(SagaQueryRequest request, String traceparent) {
        Objects.requireNonNull(request, "request");
        byte[] body = SagaEnvelopeCodec.encode(request);
        SagaHttpResponse response = transport.exchange(
                "POST", "/instances/query", body, null, traceparent);
        return decodeJson(response, HttpQueryResponse.class,
                decoded -> SagaReadResponse.query(new SagaQueryPage(decoded.sagas(), decoded.nextPageToken()), request));
    }

    /**
     * 业务作用：读取 Rust 统一审计页，保持 page token 不透明并复用实际 path 签名。
     *
     * @param request     审计目标与分页条件
     * @param traceparent 可选链路上下文
     * @return 符合页大小和 token 合同的 Rust 审计页；缺失或损坏的事实及分页合同错误为 UNCERTAIN。
     */
    @Override
    public SagaAuditPage audit(SagaAuditRequest request, String traceparent) {
        Objects.requireNonNull(request, "request");
        byte[] body = request.pageSize() == null && request.pageToken() == null
                ? new byte[0]
                : SagaEnvelopeCodec.encode(new HttpAuditBody(request.pageSize(), request.pageToken()));
        String tenant = pathSegment(request.tenantId(), "tenant_id");
        String saga = pathSegment(request.sagaId(), "saga_id");
        SagaHttpResponse response = transport.exchange(
                "GET", "/instances/" + tenant + "/" + saga + "/audit", body, null, traceparent);
        return decodeJson(response, HttpAuditResponse.class,
                decoded -> SagaReadResponse.audit(new SagaAuditPage(decoded.records(), decoded.nextPageToken()), request));
    }

    /**
     * 业务作用：约束身份的 HTTP 路径表示，避免编码歧义改变签名目标或资源范围。
     * @param value 未编码的业务身份
     * @param field 身份字段名称
     * @return 合法 URI unreserved 身份原文；其它表示拒绝。
     */
    private static String pathSegment(String value, String field) {
        SagaIds.requireOpaque(value, field, 256);
        if (value.chars().anyMatch(character -> !(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '.' && character != '_' && character != '-'
                && character != '~')) {
            throw new SagaProtocolException(field + " must use URI unreserved ASCII for managed HTTP paths");
        }
        return value;
    }

    /**
     * 业务作用：将 JSON 解码、远端字段门禁和领域响应构造置于同一不确定结果边界，保留发送前本地校验的分类。
     *
     * @param response 已完整收到的成功 HTTP 响应
     * @param type     远端响应 DTO 类型
     * @param mapper   对已解码响应执行领域构造和业务合同校验，不包含请求编码或网络调用
     * @param <T>      远端响应类型
     * @param <R>      领域响应类型
     * @return 完整且通过领域校验的响应；远端合同损坏以带原 HTTP 状态的 UNCERTAIN 表示。
     */
    private static <T, R> R decodeJson(SagaHttpResponse response, Class<T> type, Function<T, R> mapper) {
        String contentType = response.firstHeader("content-type");
        if (!isJsonContentType(contentType)) {
            throw uncertain(response, "Saga HTTP response content type is not JSON", null);
        }
        try {
            return mapper.apply(SagaEnvelopeCodec.decodeStrict(response.body(), type));
        } catch (SagaProtocolException | IllegalArgumentException exception) {
            // 成功状态码不能证明返回事实完整；原请求可能已提交，不能让远端合同损坏变成本地确定拒绝。
            throw uncertain(response, "Saga HTTP response is not a valid contract", exception);
        }
    }

    /**
     * 业务作用：保留远端响应状态并隐藏正文，阻止调用方把损坏响应当成业务拒绝。
     *
     * @param response 已收到的 HTTP 响应
     * @param message  固定的低敏错误描述
     * @param cause    本地响应解码或校验异常，可为空
     * @return 不携带远端正文的 UNCERTAIN 异常。
     */
    private static SagaHttpException uncertain(SagaHttpResponse response, String message, Throwable cause) {
        return new SagaHttpException(SagaHttpDisposition.UNCERTAIN, response.statusCode(), new byte[0], message, cause);
    }

    /**
     * 业务作用：在领域解码前核对响应媒体，不能把任意成功正文当作 JSON 收据。
     * @param value 可含参数的 Content-Type
     * @return 仅 application/json 媒体成立。
     */
    private static boolean isJsonContentType(String value) {
        if (value == null) {
            return false;
        }
        String mediaType = value.split(";", 2)[0].trim();
        return "application/json".equalsIgnoreCase(mediaType);
    }

    private record HttpStartResponse(
            String status,
            @JsonProperty("request_digest") String requestDigest,
            SagaSnapshotView saga) {
        /**
         * 业务作用：在解码阶段拒绝缺失裁决、请求摘要或快照的 start 响应。
         *
         * @param status        Rust 远端裁决文本
         * @param requestDigest Rust 计算的请求语义摘要
         * @param saga          Rust 权威快照
         *                      返回：必需字段完整时保留原值；缺失或非法摘要抛出协议异常。
         */
        private HttpStartResponse {
            // 缺少任一成功证据都不能确认投递；字段门禁先于 disposition 分支和收据构造。
            if (status == null || status.isBlank() || saga == null) {
                throw new SagaProtocolException("Rust HTTP start response is incomplete");
            }
            SagaIds.requireDigest(requestDigest);
        }
    }

    private record HttpQueryResponse(
            List<SagaSnapshotView> sagas,
            @JsonProperty("next_page_token") String nextPageToken) {
        /**
         * 业务作用：区分远端明确空页与缺失查询事实，拒绝空快照元素。
         *
         * @param sagas         必须存在的快照集合，允许空数组
         * @param nextPageToken Rust 不透明后续游标，可为空
         *                      返回：完整集合冻结为只读副本；缺失集合或空元素抛出协议异常。
         */
        private HttpQueryResponse {
            // 缺失集合不是不存在实例的证据；在响应解码边界内拒绝，避免静默丢失查询事实。
            if (sagas == null || sagas.stream().anyMatch(Objects::isNull)) {
                throw new SagaProtocolException("Rust HTTP query page is incomplete");
            }
            sagas = List.copyOf(sagas);
        }
    }

    private record HttpAuditBody(
            @JsonProperty("page_size") Integer pageSize,
            @JsonProperty("page_token") String pageToken) {
    }

    private record HttpAuditResponse(
            List<SagaAuditRecordView> records,
            @JsonProperty("next_page_token") String nextPageToken) {
        /**
         * 业务作用：在 HTTP 响应解码边界内拒绝缺失记录集合或含空事实的审计页。
         *
         * @param records       必须存在的事实集合，允许空页
         * @param nextPageToken Rust 提供的不透明后续游标，可为空
         *                      返回：完整集合冻结为只读副本；合同损坏抛出协议异常，由解码边界转换为 UNCERTAIN。
         */
        private HttpAuditResponse {
            // 缺失记录集合无法证明空页；在网络响应边界内拒绝，避免误作本地参数错误或静默遗漏事实。
            if (records == null || records.stream().anyMatch(Objects::isNull)) {
                throw new SagaProtocolException("Rust HTTP audit page is incomplete");
            }
            records = List.copyOf(records);
        }
    }
}
