package io.github.nasaruntime.saga;

import io.github.nasaruntime.core.base.TimingWheel;
import io.github.nasaruntime.saga.rc.SagaExecutionConfig;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * 为通信组件选择执行器；共享资源由时间轮拥有，调用方只取得任务提交能力。
 */
final class SagaExecutors {

    /**
     * 业务作用：限制执行器选择为构造阶段的静态入口，避免产生独立的资源所有者。
     * 参数说明：无。
     * 返回：不对外创建实例。
     */
    private SagaExecutors() {}

    /**
     * 业务作用：按通信策略启动并借用默认时间轮的任务执行器，保留共享资源的统一停机责任。
     *
     * @param config 通信组件构造时冻结的执行策略
     * @return 共享虚拟线程执行器；关闭接入时为 null，表示使用通信库默认值；并发停机时可能拒绝构造或后续提交
     */
    static Executor resolve(SagaExecutionConfig config) {
        // 关闭接入时不触碰 TimingWheel，避免配置选择本身创建线程资源或注册停机动作。
        if (!Objects.requireNonNull(config, "executionConfig").useTimingWheelExecutor()) {
            return null;
        }
        TimingWheel wheel = TimingWheel.of();
        // start 幂等且在内部串行初始化；先完成启动再借用执行器，避免取得上次停机留下的执行器。
        wheel.start();
        ExecutorService executor = wheel.getVirtualExecutor();
        // 全局停机与组件构造由宿主串行管理；已观察到停机时明确拒绝，不切换到另一套默认资源。
        if (!TimingWheel.isStarted() || executor.isShutdown()) {
            throw new IllegalStateException("Saga timing wheel executor is shutting down");
        }
        return executor;
    }
}
