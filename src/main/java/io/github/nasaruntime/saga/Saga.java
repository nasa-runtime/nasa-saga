package io.github.nasaruntime.saga;

import java.lang.annotation.ElementType;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Java participant 对应 Rust `#[saga(...)]` 的步骤合同注解。
 *
 * <p>该注解只声明单个 workflow step 的业务合同；Inbox、gate、本地事务、result Outbox、认证和
 * capability lease 仍由 participant runtime 与宿主应用共同持有。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Saga {

    /**
     * 业务作用：声明该步骤所属的 workflow。
     *
     * @return Rust `workflow` 参数
     */
    String workflow();

    /**
     * 业务作用：固定该步骤使用的 definition 版本。
     *
     * @return Rust `version` 参数
     */
    int version();

    /**
     * 业务作用：声明 workflow 中由本 participant 执行的 step。
     *
     * @return Rust `step` 参数
     */
    String step();

    /**
     * 业务作用：声明多事务域部署中选择本地数据库的 binding；空字符串表示未指定。
     *
     * @return Rust `binding` 参数
     */
    String binding() default "";

    /**
     * 业务作用：固定 command payload 的规范媒体类型。
     *
     * @return Rust `content_type` 参数
     */
    String contentType() default "application/json";

    /**
     * 业务作用：固定 command payload 的稳定 schema 身份。
     *
     * @return Rust `schema_id` 参数
     */
    String schemaId() default "";

    /**
     * 业务作用：声明正向效果是否进入逆序补偿计划。
     *
     * @return Rust `compensable` 参数
     */
    boolean compensable() default true;

    /**
     * 业务作用：声明外部效果结果未知时是否允许进入解决流程。
     *
     * @return Rust `allow_unknown` 参数
     */
    boolean allowUnknown() default false;

    /**
     * 业务作用：声明取消屏障形态，取 Rust 的 `local-fenceable`、`resolve-only` 或
     * `externally-cancellable`。
     *
     * @return Rust `cancel_mode` 参数
     */
    String cancelMode() default "local-fenceable";

    /**
     * 业务作用：声明应用是否允许由 participant runtime 以无参构造方式托管该步骤。
     *
     * @return Rust `managed` 参数
     */
    boolean managed() default false;
}
