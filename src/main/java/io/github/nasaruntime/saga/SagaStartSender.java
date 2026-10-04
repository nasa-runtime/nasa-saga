package io.github.nasaruntime.saga;

import java.time.Duration;

/**
 * 将已提交 intent 的冻结请求交给远端，不持有本地数据库事务。
 */
public interface SagaStartSender {
    /**
     * 业务作用：为网络调用及本地结算保留同一次 lease 内的强制调用预算。
     * 参数说明：无。
     *
     * @return 完整远端调用的正超时。
     */
    Duration requestTimeout();

    /**
     * 业务作用：按冻结 JSON 请求映射所选 transport，不重新生成业务身份或正文。
     *
     * @param body        已在业务事务中冻结的 start JSON
     * @param traceparent 同事务保存的链路上下文
     * @return Rust 明确 Committed 或 Duplicate 的完整远端收据；dispatcher 必须按冻结 intent 复验身份后才能结算成功，其它情况抛出异常。
     */
    SagaStartResult startRaw(byte[] body, String traceparent);
}
