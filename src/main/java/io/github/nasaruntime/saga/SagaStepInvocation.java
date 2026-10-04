package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaContext;
import io.github.nasaruntime.saga.rc.SagaPayload;
import io.github.nasaruntime.saga.rc.SagaStepResult;

import java.util.concurrent.CompletionStage;

/**
 * participant transaction wrapper 调用 `@Saga` service 的业务回调。
 */
@FunctionalInterface
public interface SagaStepInvocation {

    /**
     * 业务作用：在 Inbox/gate 已获得本地事务控制后执行对应业务阶段，并返回可持久化结论。
     *
     * @param context 由 wrapper 构造的身份上下文；cancel/resolve 已绑定目标 effect
     * @param payload 已按 descriptor 校验的原始正文，可为空
     * @return 业务结论；异常表示本次没有可提交事实，事务必须回滚并保留原 command 重投
     */
    CompletionStage<SagaStepResult> invoke(SagaContext context, SagaPayload payload);
}
