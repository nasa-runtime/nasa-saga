package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaEnvelopeCodec;
import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rust Definition Catalog 接受的 HTTP 或 gRPC capability descriptor。
 *
 * @param payloadContract       participant 接受的正文合同，可为空表示无 schema JSON
 * @param tenant                能力获准服务的租户
 * @param owner                 definition 中声明的逻辑服务身份
 * @param replicaIdentity       当前副本的稳定租约身份
 * @param workflow              workflow 名称
 * @param definitionVersion     definition 版本
 * @param step                  步骤名称
 * @param compensation          补偿能力，取 {@code compensable} 或 {@code non-compensable}
 * @param cancelMode            取消形态，取 Rust 的 kebab-case 名称
 * @param allowUnknown          是否允许未知结果
 * @param resolutionMode        未知结果解决模式；禁止未知时必须为空
 * @param transport             数据面协议，取 {@code http} 或 {@code grpc}
 * @param endpoint              participant origin，不包含 Saga path
 * @param effectiveSagaBasePath HTTP 的完整 Saga base path；gRPC 不使用路径，必须为空
 * @param resultContractDigest  result credential 或后端合同摘要
 * @param routeGeneration       初始应为正数；Rust Catalog 会分配实际代际
 * @param requestedLeaseMs      请求的 capability lease 时长
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SagaCapabilityDescriptor(
        @JsonProperty("payload_contract") SagaCapabilityPayloadContract payloadContract,
        @JsonProperty("tenant") String tenant,
        @JsonProperty("owner") String owner,
        @JsonProperty("replica_identity") String replicaIdentity,
        @JsonProperty("workflow") String workflow,
        @JsonProperty("definition_version") int definitionVersion,
        @JsonProperty("step") String step,
        @JsonProperty("compensation") String compensation,
        @JsonProperty("cancel_mode") String cancelMode,
        @JsonProperty("allow_unknown") boolean allowUnknown,
        @JsonProperty("resolution_mode") String resolutionMode,
        @JsonProperty("transport") String transport,
        @JsonProperty("endpoint") String endpoint,
        @JsonProperty("effective_saga_base_path") String effectiveSagaBasePath,
        @JsonProperty("result_contract_digest") String resultContractDigest,
        @JsonProperty("route_generation") long routeGeneration,
        @JsonProperty("requested_lease_ms") long requestedLeaseMs) {

    /**
     * 业务作用：冻结跨语言 capability 的身份、步骤策略、地址和租约边界，避免把未经校验的路由送入 Catalog。
     * 参数说明：身份、步骤和租约字段沿用记录合同；transport 决定 endpoint 与有效 HTTP path 的约束。
     * 返回：有效不可变 descriptor；协议混用、非法身份或非正租约均拒绝构造。
     */
    public SagaCapabilityDescriptor {
        SagaIds.requireOpaque(tenant, "tenant", 256);
        SagaIds.requireStructured(owner, "owner");
        SagaIds.requireStructured(replicaIdentity, "replica_identity");
        SagaIds.requireStructured(workflow, "workflow");
        SagaIds.requirePositive(definitionVersion, "definition_version");
        SagaIds.requireStructured(step, "step");
        requireOneOf(compensation, "compensation", "compensable", "non-compensable");
        requireOneOf(cancelMode, "cancel_mode", "local-fenceable", "externally-cancellable", "resolve-only");
        if (allowUnknown) {
            requireOneOf(resolutionMode, "resolution_mode", "callback", "poll", "manual");
        } else if (resolutionMode != null) {
            throw new SagaProtocolException("resolution_mode must be absent when unknown is forbidden");
        }
        requireOneOf(transport, "transport", "http", "grpc");
        requireHttpOrigin(endpoint);
        if ("http".equals(transport)) {
            requireHttpBasePath(effectiveSagaBasePath);
        } else if (!"https".equals(URI.create(endpoint).getScheme()) || effectiveSagaBasePath != null) {
            throw new SagaProtocolException("gRPC capability requires an HTTPS origin and no HTTP base path");
        }
        SagaIds.requireDigest(resultContractDigest);
        if (routeGeneration <= 0 || requestedLeaseMs <= 0) {
            throw new SagaProtocolException("capability route generation and lease must be positive");
        }
    }

    /**
     * 业务作用：确认该 descriptor 可以通过 Rust managed HTTP Registry 登记，防止 HTTP transport 与其它协议混用。
     * 参数说明：无。
     * 返回：HTTP 合同匹配时通过，其它 transport 抛出协议异常。
     */
    public void validateForHttpRegistration() {
        if (!"http".equals(transport)) {
            throw new SagaProtocolException("HTTP capability registration requires transport http");
        }
    }

    /**
     * 业务作用：按 Rust capability 字段顺序和服务端分配的路由代际计算收据绑定摘要。
     *
     * @param assignedRouteGeneration Catalog 确认的正路由代际，不使用登记请求中的建议值
     * @return 不含租期建议、包含完整路由合同的小写 SHA-256；代际无效时拒绝
     */
    public String digestForRoute(long assignedRouteGeneration) {
        if (assignedRouteGeneration <= 0) {
            throw new SagaProtocolException("capability route generation must be positive");
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        if (payloadContract != null) {
            canonical.put("payload_contract", payloadContract);
        }
        canonical.put("tenant", tenant);
        canonical.put("owner", owner);
        canonical.put("replica_identity", replicaIdentity);
        canonical.put("workflow", workflow);
        canonical.put("definition_version", definitionVersion);
        canonical.put("step", step);
        canonical.put("compensation", compensation);
        canonical.put("cancel_mode", cancelMode);
        canonical.put("allow_unknown", allowUnknown);
        // Rust 的 resolution_mode 为 None 时仍序列化 null，摘要不能沿用 descriptor 的省略空值策略。
        canonical.put("resolution_mode", resolutionMode);
        canonical.put("transport", transport);
        canonical.put("endpoint", endpoint);
        canonical.put("effective_saga_base_path", effectiveSagaBasePath);
        canonical.put("result_contract_digest", resultContractDigest);
        canonical.put("route_generation", assignedRouteGeneration);
        canonical.put("requested_lease_ms", 0);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(SagaEnvelopeCodec.encode(canonical)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for capability receipts", exception);
        }
    }

    /**
     * 业务作用：限制 capability 策略字段为 Rust 支持的封闭取值。
     *
     * @param value   候选值
     * @param field   协议字段名
     * @param allowed 该字段支持的取值
     *                返回：匹配时不改变配置；未知取值抛出协议异常。
     */
    private static void requireOneOf(String value, String field, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw new SagaProtocolException(field + " is not a supported capability value");
    }

    /**
     * 业务作用：阻止凭据、查询和业务路径进入 capability origin。
     *
     * @param value 候选 HTTP origin
     *              返回：规范 origin 校验通过；不合法的地址抛出协议异常。
     */
    private static void requireHttpOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    && !"/".equals(uri.getRawPath()))) {
                throw new SagaProtocolException("capability endpoint must be an HTTP origin");
            }
        } catch (IllegalArgumentException exception) {
            throw new SagaProtocolException("capability endpoint is not a valid HTTP origin", exception);
        }
    }

    /**
     * 业务作用：保持 capability 路由与 HTTP 签名使用相同的规范路径。
     *
     * @param value 候选 Saga base path
     *              返回：路径无编码歧义时通过；否则抛出协议异常。
     */
    private static void requireHttpBasePath(String value) {
        if (value == null || value.isEmpty() || "/".equals(value) || !value.startsWith("/")
                || value.endsWith("/") || value.contains("//") || value.contains("%")
                || value.contains("?") || value.contains("#")
                || value.chars().anyMatch(character -> !(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '/' && character != '-' && character != '_'
                && character != '.' && character != '~')
                || Arrays.stream(value.split("/", -1)).anyMatch(segment -> ".".equals(segment)
                || "..".equals(segment))) {
            throw new SagaProtocolException("effective_saga_base_path is not canonical");
        }
    }
}
