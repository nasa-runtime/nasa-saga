package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.CancelOutcome;
import io.github.nasaruntime.saga.rc.CompensationOutcome;
import io.github.nasaruntime.saga.rc.SagaContext;
import io.github.nasaruntime.saga.rc.SagaOutcome;
import io.github.nasaruntime.saga.rc.SagaPayload;
import io.github.nasaruntime.saga.rc.SagaStepDescriptor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * `@Saga` 的编译期合同检查器，对应 Rust `#[saga]` 对 impl 形态和参数的前置检查。
 *
 * <p>处理器不生成全局 Saga server，也不生成数据库代码；它只阻止 service 形态、方法签名、
 * managed 构造条件和同一编译单元内重复 step 合同进入产物。</p>
 */
@SupportedAnnotationTypes("io.github.nasaruntime.saga.Saga")
public final class SagaAnnotationProcessor extends AbstractProcessor {

    private final Map<String, Element> descriptors = new HashMap<>();

    private Types types;
    private Elements elements;

    /**
     * 业务作用：按运行处理器的编译器声明源码支持范围，使 JDK 21 及以上的应用均可进行步骤合同校验。
     * 参数说明：无。
     *
     * @return 当前编译器支持的最新源码级别；不改变 SDK 的 Java 21 字节码基线
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * 业务作用：保存类型系统和诊断工具，供每轮注解处理复用同一合同判断。
     *
     * @param processingEnvironment 当前 Java 编译环境
     */
    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        this.types = processingEnvironment.getTypeUtils();
        this.elements = processingEnvironment.getElementUtils();
    }

    /**
     * 业务作用：检查全部 `@Saga` service，确保生成的 participant adapter 有 Rust 对等的能力形态。
     *
     * @param annotations      当前轮次发现的注解类型
     * @param roundEnvironment 当前轮次源码元素
     * @return 已处理 `@Saga`，不让其它处理器重复解释该注解
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (Element element : roundEnvironment.getElementsAnnotatedWith(Saga.class)) {
            validateElement(element);
        }
        return true;
    }

    /**
     * 业务作用：校验单个 `@Saga` 类型的 route 合同、业务接口和 managed 条件。
     *
     * @param element 带 `@Saga` 的源码元素
     */
    private void validateElement(Element element) {
        if (element.getKind() != ElementKind.CLASS) {
            error(element, "@Saga 只能标注具体 service class");
            return;
        }
        TypeElement service = (TypeElement) element;
        Saga annotation = service.getAnnotation(Saga.class);
        if (annotation == null) {
            return;
        }
        SagaStepDescriptor descriptor;
        try {
            descriptor = SagaStepDescriptor.fromAnnotation(annotation, service.getSimpleName().toString());
        } catch (RuntimeException exception) {
            error(service, exception.getMessage());
            return;
        }

        TypeElement sagaStepType = elements.getTypeElement(SagaStep.class.getName());
        if (sagaStepType == null || !types.isAssignable(
                types.erasure(service.asType()), types.erasure(sagaStepType.asType()))) {
            error(service, "@Saga service 必须实现 SagaStep");
            return;
        }
        if (!service.getTypeParameters().isEmpty()) {
            error(service, "@Saga service 不支持泛型类型");
        }
        if (service.getModifiers().contains(Modifier.ABSTRACT)) {
            error(service, "@Saga service 不能是 abstract");
        }
        rejectTransactionalAnnotations(service);
        validateRequiredMethods(service, descriptor);
        validateManaged(service, descriptor);

        String key = descriptor.workflow() + "\u0000" + descriptor.definitionVersion() + "\u0000" + descriptor.step();
        Element previous = descriptors.putIfAbsent(key, service);
        if (previous != null) {
            error(service, "同一编译单元重复声明 Saga step；已有声明类型："
                    + previous.getSimpleName());
        }
    }

    /**
     * 业务作用：按 cancel mode 检查 Rust `SagaStep` 及可选能力在 Java service 上都有显式方法实现。
     *
     * @param service    待检查 service
     * @param descriptor 已校验的步骤 descriptor
     */
    private void validateRequiredMethods(TypeElement service, SagaStepDescriptor descriptor) {
        requireMethod(service, "execute", SagaOutcome.class.getName());
        requireMethod(service, "compensate", CompensationOutcome.class.getName());
        if ("externally-cancellable".equals(descriptor.cancelMode())) {
            requireMethod(service, "cancel", CancelOutcome.class.getName());
        }
        if (!"local-fenceable".equals(descriptor.cancelMode())) {
            requireMethod(service, "resolve", SagaOutcome.class.getName());
        }
    }

    /**
     * 业务作用：拒绝把另一个声明式事务边界叠加到 `@Saga` adapter，避免业务调用绕过 Inbox/gate/Outbox 顺序。
     *
     * @param service 待检查的 service 类型
     */
    private void rejectTransactionalAnnotations(TypeElement service) {
        if (isTransactional(service)) {
            error(service, "@Saga service 不能叠加 transactional 注解；本地事务由 SagaParticipantTransaction 独占");
        }
        for (ExecutableElement method : ElementFilter.methodsIn(service.getEnclosedElements())) {
            if (isTransactional(method)) {
                error(method, "@Saga 方法不能叠加 transactional 注解；本地事务由 SagaParticipantTransaction 独占");
            }
        }
    }

    /**
     * 业务作用：按注解简单名识别宿主事务标记，不引入具体 Web 或事务框架依赖。
     *
     * @param element service 或业务方法
     * @return 存在 `Transactional` 或 `transactional` 标记时返回真
     */
    private boolean isTransactional(Element element) {
        return element.getAnnotationMirrors().stream().anyMatch(annotation -> {
            String name = annotation.getAnnotationType().asElement().getSimpleName().toString();
            return "Transactional".equals(name) || "transactional".equals(name);
        });
    }

    /**
     * 业务作用：检查一个 phase 方法的参数、CompletionStage 结果和 public 非抽象可调用边界。
     *
     * @param service        待检查 service
     * @param name           phase 方法名
     * @param resultTypeName 结果类型全名
     */
    private void requireMethod(TypeElement service, String name, String resultTypeName) {
        TypeElement contextType = elements.getTypeElement(SagaContext.class.getName());
        TypeElement payloadType = elements.getTypeElement(SagaPayload.class.getName());
        TypeElement completionStageType = elements.getTypeElement(CompletionStage.class.getName());
        TypeElement expectedResultType = elements.getTypeElement(resultTypeName);
        if (contextType == null || payloadType == null || completionStageType == null || expectedResultType == null) {
            error(service, "@Saga 编译期依赖类型不可用");
            return;
        }
        TypeMirror completionStageErasure = types.erasure(completionStageType.asType());
        List<? extends Element> declared = service.getEnclosedElements();
        for (ExecutableElement method : ElementFilter.methodsIn(declared)) {
            if (!method.getSimpleName().contentEquals(name)) {
                continue;
            }
            List<? extends TypeMirror> parameters = method.getParameters().stream()
                    .map(parameter -> types.erasure(parameter.asType()))
                    .toList();
            if (parameters.size() != 2
                    || !types.isSameType(parameters.get(0), types.erasure(contextType.asType()))
                    || !types.isSameType(parameters.get(1), types.erasure(payloadType.asType()))
                    || !types.isSameType(types.erasure(method.getReturnType()), completionStageErasure)
                    || !returnsExpectedType(method, expectedResultType)
                    || method.getModifiers().contains(Modifier.ABSTRACT)
                    || !method.getModifiers().contains(Modifier.PUBLIC)) {
                error(method, "@Saga " + name + " 必须是 public CompletionStage<"
                        + resultTypeName.substring(resultTypeName.lastIndexOf('.') + 1)
                        + ">(SagaContext, SagaPayload)");
            }
            return;
        }
        error(service, "@Saga service 缺少 public " + name + "(SagaContext, SagaPayload)");
    }

    /**
     * 业务作用：校验 CompletionStage 的泛型结果，避免 raw stage 隐藏错误的 phase 结论类型。
     *
     * @param method             待检查方法
     * @param expectedResultType 期望结果类型
     * @return 泛型参数精确匹配时返回真
     */
    private boolean returnsExpectedType(ExecutableElement method, TypeElement expectedResultType) {
        if (!(method.getReturnType() instanceof DeclaredType completionStage)
                || completionStage.getTypeArguments().size() != 1) {
            return false;
        }
        return types.isSameType(
                types.erasure(completionStage.getTypeArguments().get(0)),
                types.erasure(expectedResultType.asType()));
    }

    /**
     * 业务作用：检查 managed service 是否具备 runtime 可用的无参构造入口。
     *
     * @param service    待检查 service
     * @param descriptor 步骤 descriptor
     */
    private void validateManaged(TypeElement service, SagaStepDescriptor descriptor) {
        if (!descriptor.managed()) {
            return;
        }
        List<ExecutableElement> constructors = ElementFilter.constructorsIn(service.getEnclosedElements());
        if (constructors.isEmpty()) {
            return;
        }
        boolean hasNoArg = constructors.stream().anyMatch(constructor ->
                constructor.getParameters().isEmpty()
                        && !constructor.getModifiers().contains(Modifier.PRIVATE));
        if (!hasNoArg) {
            error(service, "managed=true 要求可访问的无参构造函数");
        }
    }

    /**
     * 业务作用：把合同拒绝定位到声明源码，阻止不完整步骤进入编译产物。
     *
     * @param element 错误位置
     * @param message 脱敏诊断信息
     */
    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
