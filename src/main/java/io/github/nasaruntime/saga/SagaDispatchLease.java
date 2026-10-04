package io.github.nasaruntime.saga;

import java.util.concurrent.TimeUnit;

/**
 * 本次 dispatcher 调用的绝对期限与单调预算，不从后续查询借用新的执行资格。
 */
final class SagaDispatchLease {

    private final long untilMs;
    private final long startedNanos = System.nanoTime();
    private final long budgetMs;

    /**
     * 业务作用：在领取前冻结本次调用的 lease 预算，领取事务耗时同样占用执行资格。
     *
     * @param nowMs   调度时刻
     * @param untilMs 本次领取的到期时刻
     *                返回：独立于后续墙钟回拨的本轮预算。
     */
    SagaDispatchLease(long nowMs, long untilMs) {
        this.untilMs = untilMs;
        this.budgetMs = untilMs - Math.max(nowMs, System.currentTimeMillis());
    }

    /**
     * 业务作用：在发送或回写前复验资格，预算不足时保留 IN_FLIGHT 供到期重领。
     *
     * @param requiredMs 接下来网络动作所需预算；本地回写为零
     *                   返回：仍有充足预算时通过，否则拒绝动作，不尝试采用新 token。
     */
    void requireRemaining(long requiredMs) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        if (untilMs - System.currentTimeMillis() <= requiredMs || budgetMs - elapsedMs <= requiredMs) {
            throw new SagaPersistenceException("dispatcher lease budget exhausted", null);
        }
    }
}
