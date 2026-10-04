package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

/**
 * 正向 execute 或 resolve 的可提交结果，对应 Rust `SagaOutcome` 的状态部分。
 *
 * @param status     SUCCEEDED、REJECTED、UNKNOWN 或 HALTED
 * @param reasonCode 非成功状态的稳定原因码
 */
public record SagaOutcome(String status, String reasonCode) {

    /**
     * 业务作用：限制 execute/resolve 只能提交 Rust 接受的四种业务状态。
     */
    public SagaOutcome {
        if (!"SUCCEEDED".equals(status) && !"REJECTED".equals(status)
                && !"UNKNOWN".equals(status) && !"HALTED".equals(status)) {
            throw new SagaProtocolException("Saga outcome status is invalid");
        }
        if ("SUCCEEDED".equals(status)) {
            if (reasonCode != null) {
                throw new SagaProtocolException("successful Saga outcome cannot carry a reason");
            }
        } else {
            SagaIds.requireReasonCode(reasonCode);
        }
    }

    /**
     * 业务作用：表达已提交的正向成功事实。
     *
     * @return 成功结果
     */
    public static SagaOutcome succeeded() {
        return new SagaOutcome("SUCCEEDED", null);
    }

    /**
     * 业务作用：表达确定性业务拒绝，并让 Orchestrator 进入其定义的后续状态。
     *
     * @param reasonCode 稳定拒绝原因码
     * @return 拒绝结果
     */
    public static SagaOutcome rejected(String reasonCode) {
        return new SagaOutcome("REJECTED", reasonCode);
    }

    /**
     * 业务作用：表达外部效果仍未裁决，阻止本地代码把未知降级为拒绝。
     *
     * @param reasonCode 稳定未知原因码
     * @return 未知结果
     */
    public static SagaOutcome unknown(String reasonCode) {
        return new SagaOutcome("UNKNOWN", reasonCode);
    }

    /**
     * 业务作用：表达不变量破坏并冻结自动推进。
     *
     * @param reasonCode 稳定冻结原因码
     * @return 冻结结果
     */
    public static SagaOutcome halted(String reasonCode) {
        return new SagaOutcome("HALTED", reasonCode);
    }
}
