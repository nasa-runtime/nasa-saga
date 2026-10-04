package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.Saga;
import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;
import io.github.nasaruntime.saga.SagaStep;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * `@Saga` 在 Java participant 中的运行时合同投影，对应 Rust 宏生成的 `SagaStepDescriptor`。
 *
 * <p>descriptor 是本地步骤唯一的路由和能力声明来源。它只描述本 participant 的一个 step，
 * 不持有 Rust Orchestrator 的实例状态、definition 状态或调度权。</p>
 *
 * @param payloadContentType 业务正文规范媒体类型
 * @param payloadSchemaId    业务正文稳定 schema 身份
 * @param workflow           workflow 名称
 * @param definitionVersion  definition 版本
 * @param step               step 名称
 * @param binding            本地事务域 binding；未声明时为 {@code null}
 * @param serviceType        Java service 类型名
 * @param compensation       补偿能力，取 {@code compensable} 或 {@code non-compensable}
 * @param cancelMode         取消形态
 * @param allowUnknown       是否允许正向结果未知
 * @param resolutionMode     解决模式；当前非本地取消形态固定为 {@code poll}
 * @param managed            是否允许 participant runtime 托管 service 实例
 * @param source             descriptor 来源，用于诊断合同冲突
 */
public record SagaStepDescriptor(
        String payloadContentType,
        String payloadSchemaId,
        String workflow,
        int definitionVersion,
        String step,
        String binding,
        String serviceType,
        String compensation,
        String cancelMode,
        boolean allowUnknown,
        String resolutionMode,
        boolean managed,
        String source) {

    /**
     * 业务作用：冻结注解参数并执行与 Rust `#[saga]` 相同的组合校验，避免不自洽的能力进入运行期。
     */
    public SagaStepDescriptor {
        new SagaCapabilityPayloadContract(payloadContentType, payloadSchemaId);
        SagaIds.requireStructured(workflow, "workflow");
        SagaIds.requirePositive(definitionVersion, "definition_version");
        SagaIds.requireStructured(step, "step");
        if (binding != null && !binding.isEmpty()) {
            SagaIds.requireStructured(binding, "binding");
        } else {
            binding = null;
        }
        SagaIds.requireStructured(serviceType, "service_type");
        requireOneOf(compensation, "compensation", "compensable", "non-compensable");
        requireOneOf(cancelMode, "cancel_mode", "local-fenceable", "resolve-only", "externally-cancellable");
        if ("local-fenceable".equals(cancelMode)) {
            if (allowUnknown || resolutionMode != null) {
                throw new SagaProtocolException(
                        "local-fenceable step cannot allow unknown or resolution");
            }
        } else {
            if (!allowUnknown || !"poll".equals(resolutionMode)) {
                throw new SagaProtocolException(
                        "non-local cancel mode requires allow_unknown and poll resolution");
            }
        }
        if (source == null || source.isBlank()) {
            throw new SagaProtocolException("descriptor source is required");
        }
    }

    /**
     * 业务作用：从已实例化的 Java service 读取 `@Saga` 合同，防止运行时绕过注解使用未声明步骤。
     *
     * @param serviceType participant service 类型
     * @return 与该类型绑定的步骤 descriptor
     */
    public static SagaStepDescriptor from(Class<?> serviceType) {
        Objects.requireNonNull(serviceType, "serviceType");
        if (!SagaStep.class.isAssignableFrom(serviceType)) {
            throw new SagaProtocolException("@Saga service must implement SagaStep");
        }
        Saga annotation = serviceType.getAnnotation(Saga.class);
        if (annotation == null) {
            throw new SagaProtocolException("SagaStep service is missing @Saga");
        }
        SagaStepDescriptor descriptor = from(annotation, serviceType.getSimpleName(), serviceType.getName());
        validateServiceType(serviceType, descriptor);
        return descriptor;
    }

    /**
     * 业务作用：为注解处理器和运行时共用一套参数组合校验，保持编译期与反射装配的边界一致。
     *
     * @param annotation      `@Saga` 参数
     * @param serviceTypeName descriptor 中的 service 类型名
     * @param source          合同来源
     * @return 已校验的 descriptor
     */
    public static SagaStepDescriptor from(Saga annotation, String serviceTypeName, String source) {
        Objects.requireNonNull(annotation, "annotation");
        return new SagaStepDescriptor(
                annotation.contentType(),
                annotation.schemaId(),
                annotation.workflow(),
                annotation.version(),
                annotation.step(),
                annotation.binding(),
                serviceTypeName,
                annotation.compensable() ? "compensable" : "non-compensable",
                annotation.cancelMode(),
                annotation.allowUnknown(),
                resolutionMode(annotation.cancelMode()),
                annotation.managed(),
                source);
    }

    /**
     * 业务作用：从编译期合同创建 descriptor，避免 annotation processor 另行复制参数组合规则。
     *
     * @param annotation      `@Saga` 参数
     * @param serviceTypeName 编译期 service 类型名
     * @return 已校验 descriptor
     */
    public static SagaStepDescriptor fromAnnotation(Saga annotation, String serviceTypeName) {
        return from(annotation, serviceTypeName, serviceTypeName);
    }

    /**
     * 业务作用：复验 command 是否精确投递到本步骤，并按声明复验原始 payload 合同。
     *
     * @param command Rust Orchestrator 投递的 command
     */
    public void verifyCommand(SagaCommandEnvelope command) {
        Objects.requireNonNull(command, "command");
        command.validate();
        if (!workflow.equals(command.workflow())
                || definitionVersion != command.definitionVersion()
                || !step.equals(command.step())) {
            throw new SagaProtocolException("Saga command route does not match @Saga descriptor");
        }
        command.verifyPayloadContract(payloadContentType, payloadSchemaId);
        switch (command.phase()) {
            case "execute" -> {
            }
            case "compensate" -> {
                if (!isCompensable()) {
                    throw new SagaProtocolException("non-compensable step cannot receive compensate");
                }
            }
            case "cancel" -> {
                if ("resolve-only".equals(cancelMode)) {
                    throw new SagaProtocolException("resolve-only step cannot receive cancel");
                }
            }
            case "resolve" -> {
                if ("local-fenceable".equals(cancelMode)) {
                    throw new SagaProtocolException("local-fenceable step cannot receive resolve");
                }
            }
            default -> throw new SagaProtocolException("Saga command phase is not supported");
        }
    }

    /**
     * 业务作用：把 descriptor 投影为 Rust capability registry 的 HTTP descriptor，确保路由语义与步骤合同同源。
     *
     * @param tenant                能力所属租户
     * @param owner                 definition 中的逻辑 owner
     * @param replicaIdentity       当前副本稳定身份
     * @param endpoint              participant origin
     * @param effectiveSagaBasePath participant 实际 command path 的 base path
     * @param resultContractDigest  Rust result producer 合同摘要
     * @param requestedLeaseMs      请求租约时长
     * @return 可通过 HTTP Registry 登记的 capability
     */
    public SagaCapabilityDescriptor toHttpCapability(
            String tenant,
            String owner,
            String replicaIdentity,
            URI endpoint,
            String effectiveSagaBasePath,
            String resultContractDigest,
            long requestedLeaseMs) {
        Objects.requireNonNull(endpoint, "endpoint");
        String endpointText = endpoint.toString();
        SagaCapabilityPayloadContract payloadContract = isDefaultJson()
                ? null
                : new SagaCapabilityPayloadContract(payloadContentType, payloadSchemaId);
        return new SagaCapabilityDescriptor(
                payloadContract,
                tenant,
                owner,
                replicaIdentity,
                workflow,
                definitionVersion,
                step,
                compensation,
                cancelMode,
                allowUnknown,
                resolutionMode,
                "http",
                endpointText,
                effectiveSagaBasePath,
                resultContractDigest,
                1,
                requestedLeaseMs);
    }

    /**
     * 业务作用：从同一业务 descriptor 生成不带 HTTP path 的 gRPC 能力，避免协议切换改变步骤合同。
     *
     * @param tenant               能力所属租户
     * @param owner                definition 中的逻辑 owner
     * @param replicaIdentity      当前副本稳定身份
     * @param endpoint             双向 TLS participant origin
     * @param resultContractDigest result client certificate principal 对应的 Rust 合同摘要
     * @param requestedLeaseMs     请求租约毫秒数
     * @return gRPC capability；非 HTTPS 地址或非法业务合同拒绝。
     */
    public SagaCapabilityDescriptor toGrpcCapability(String tenant, String owner, String replicaIdentity,
                                                     URI endpoint, String resultContractDigest, long requestedLeaseMs) {
        Objects.requireNonNull(endpoint, "endpoint");
        return new SagaCapabilityDescriptor(isDefaultJson() ? null
                : new SagaCapabilityPayloadContract(payloadContentType, payloadSchemaId),
                tenant, owner, replicaIdentity, workflow, definitionVersion, step, compensation, cancelMode,
                allowUnknown, resolutionMode, "grpc", endpoint.toString(), null, resultContractDigest, 1, requestedLeaseMs);
    }

    /**
     * 业务作用：按 Rust descriptor 的能力字段判断是否使用默认无 schema JSON 合同。
     *
     * @return 默认 `application/json` 且 schema 为空时返回真
     */
    public boolean isDefaultJson() {
        return "application/json".equals(payloadContentType) && payloadSchemaId.isEmpty();
    }

    /**
     * 业务作用：把业务结果中的 UNKNOWN 按 Rust participant wrapper 的策略收敛，防止本地步骤制造无界 resolution。
     *
     * @param phase  当前 command 阶段
     * @param result service 返回的结果
     * @return 合同允许的结果；local-fenceable execute 的 UNKNOWN 转为 HALTED
     */
    public SagaStepResult normalizeResult(String phase, SagaStepResult result) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(result, "result");
        if ("execute".equals(phase) && "UNKNOWN".equals(result.status()) && !allowUnknown) {
            return SagaStepResult.halted("unknown_not_allowed");
        }
        return result;
    }

    /**
     * 业务作用：在 `managed=true` 时按 Rust `Default` 对应的无参构造创建 service，启动失败即拒绝开放路由。
     *
     * @param serviceType 已通过注解处理器或运行时合同校验的 service 类型
     * @return 新建 service 实例
     */
    public SagaStep newManagedInstance(Class<? extends SagaStep> serviceType) {
        if (!managed) {
            throw new SagaProtocolException("@Saga service is not managed");
        }
        Objects.requireNonNull(serviceType, "serviceType");
        SagaStepDescriptor actual = from(serviceType);
        if (!equals(actual)) {
            throw new SagaProtocolException("managed service descriptor does not match");
        }
        try {
            Constructor<? extends SagaStep> constructor = serviceType.getDeclaredConstructor();
            if (!Modifier.isPublic(constructor.getModifiers()) && !constructor.trySetAccessible()) {
                throw new SagaProtocolException("managed @Saga service constructor is inaccessible");
            }
            return constructor.newInstance();
        } catch (SagaProtocolException exception) {
            throw exception;
        } catch (ReflectiveOperationException exception) {
            throw new SagaProtocolException("managed @Saga service cannot be constructed", exception);
        }
    }

    /**
     * 业务作用：读取补偿能力的布尔投影，供 adapter 和 capability 生成共用。
     *
     * @return 注解声明可补偿时返回真
     */
    public boolean isCompensable() {
        return "compensable".equals(compensation);
    }

    /**
     * 业务作用：在运行时装配绕过 annotation processor 时复验 service 方法形态，防止缺失 cancel/resolve 能力的
     * participant 先取得 capability 再在 command 上失败。
     *
     * @param serviceType 待装配的 service 类型
     * @param descriptor  已校验的步骤 descriptor
     */
    private static void validateServiceType(Class<?> serviceType, SagaStepDescriptor descriptor) {
        requireDeclaredBusinessMethod(serviceType, "execute");
        requireDeclaredBusinessMethod(serviceType, "compensate");
        if ("externally-cancellable".equals(descriptor.cancelMode())) {
            requireDeclaredBusinessMethod(serviceType, "cancel");
        }
        if (!"local-fenceable".equals(descriptor.cancelMode())) {
            requireDeclaredBusinessMethod(serviceType, "resolve");
        }
        if (descriptor.managed()) {
            try {
                Constructor<?> constructor = serviceType.getDeclaredConstructor();
                if (Modifier.isPrivate(constructor.getModifiers())) {
                    throw new SagaProtocolException("managed @Saga service requires an accessible no-arg constructor");
                }
            } catch (NoSuchMethodException exception) {
                throw new SagaProtocolException("managed @Saga service requires a no-arg constructor", exception);
            }
        }
    }

    /**
     * 业务作用：确认 service 显式声明所需 phase 方法，并阻止继承的默认方法冒充 Rust 可选 trait 能力。
     *
     * @param serviceType service 类型
     * @param name        phase 方法名
     */
    private static void requireDeclaredBusinessMethod(Class<?> serviceType, String name) {
        try {
            Method method = serviceType.getDeclaredMethod(name, SagaContext.class, SagaPayload.class);
            if (!Modifier.isPublic(method.getModifiers())
                    || !CompletionStage.class.isAssignableFrom(method.getReturnType())
                    || !returnsExpectedType(method, name)) {
                throw new SagaProtocolException("@Saga " + name + " method has an invalid signature");
            }
        } catch (NoSuchMethodException exception) {
            throw new SagaProtocolException("@Saga service is missing " + name + " method", exception);
        }
    }

    /**
     * 业务作用：复验反射运行期仍保留的 CompletionStage 泛型结果，防止绕过 annotation processor 后把错误结论写入 Outbox。
     *
     * @param method 待检查的 phase 方法
     * @param name   phase 方法名
     * @return 返回类型精确对应 phase 结果时返回真
     */
    private static boolean returnsExpectedType(Method method, String name) {
        if (!(method.getGenericReturnType() instanceof ParameterizedType stage)
                || !CompletionStage.class.equals(stage.getRawType())
                || stage.getActualTypeArguments().length != 1) {
            return false;
        }
        Type expected = switch (name) {
            case "execute", "resolve" -> SagaOutcome.class;
            case "compensate" -> CompensationOutcome.class;
            case "cancel" -> CancelOutcome.class;
            default -> null;
        };
        return expected != null && expected.equals(stage.getActualTypeArguments()[0]);
    }

    /**
     * 业务作用：把 Rust cancel mode 投影为 descriptor 的 resolution mode，保证非本地步骤只能使用 poll。
     *
     * @param cancelMode 注解取消形态
     * @return local-fenceable 返回空值，其它合法形态返回 {@code poll}
     */
    private static String resolutionMode(String cancelMode) {
        return "local-fenceable".equals(cancelMode) ? null : "poll";
    }

    /**
     * 业务作用：封闭 descriptor 的枚举字段，防止未知值进入 capability 或 phase 门禁。
     *
     * @param value   待检查值
     * @param field   字段名称
     * @param allowed 合法值集合
     */
    private static void requireOneOf(String value, String field, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw new SagaProtocolException(field + " is not a supported value");
    }
}
