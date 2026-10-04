package io.github.nasaruntime.saga.rc;

/**
 * 通信组件构造时采用的执行策略；不会改变宿主业务事务、listener 或持久扫描器的调度方式。
 *
 * <p>创建配置不启动时间轮；通信组件采用启用策略时才调用默认时间轮的幂等 start 并借用虚拟线程执行器。
 * 关闭接入时保留通信库默认执行器，不访问时间轮；配置不改变既有组件。</p>
 * <p>共享执行器由时间轮拥有，关闭通信组件不停止时间轮。默认时间轮包含非守护调度线程，
 * 宿主须在全部依赖组件结束后停止时间轮；重启时间轮后须重新构造通信组件。</p>
 *
 * @param useTimingWheelExecutor 是否借用 nasa-core 默认时间轮的虚拟线程执行器；默认启用并按需启动时间轮
 */
public record SagaExecutionConfig(boolean useTimingWheelExecutor) {

    public static final SagaExecutionConfig DEFAULT = new SagaExecutionConfig();

    /**
     * 业务作用：采用共享时间轮任务执行器作为 HTTP/gRPC 通信组件的默认执行策略。
     * 参数说明：无。
     * 返回：启用共享执行器的配置；创建配置本身不启动时间轮。
     */
    public SagaExecutionConfig() {
        this(true);
    }
}
