package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaResultOutbox;

/**
 * 在网络发送前核对单条结果所需的本地业务证据，不把控制表自洽视为业务成功证明。
 */
@FunctionalInterface
public interface SagaResultOutboxEvidenceVerifier {
    /**
     * 业务作用：在本次领取后独立裁决原事件的业务语义是否仍可证明，不修改事件或业务事实。
     *
     * @param outbox 已领取的原事件、payload 与本次 fencing token
     * @return true 才允许发送；false 使原事件进入 NEEDS_ATTENTION；临时不可读可抛出异常保留退避重试。
     */
    boolean permits(SagaResultOutbox outbox);
}
