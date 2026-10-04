package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

/**
 * cancel 的三分支裁决，对应 Rust `CancelOutcome`。
 *
 * @param status CANCEL_CONFIRMED、ALREADY_TERMINAL 或 RESOLUTION_PENDING
 * @param terminalStatus ALREADY_TERMINAL 对应的正向终态，可为空
 * @param reasonCode 未决或终态原因码，可为空
 */
public record CancelOutcome(String status, String terminalStatus, String reasonCode) {

    /**
     * 业务作用：限制取消只能提交真实屏障、已知正向终态或仍需解决三种裁决。
     */
    public CancelOutcome {
        if (!"CANCEL_CONFIRMED".equals(status) && !"ALREADY_TERMINAL".equals(status)
                && !"RESOLUTION_PENDING".equals(status)) {
            throw new SagaProtocolException("cancel outcome status is invalid");
        }
        if ("ALREADY_TERMINAL".equals(status)) {
            if (!"SUCCEEDED".equals(terminalStatus) && !"REJECTED".equals(terminalStatus)
                    && !"HALTED".equals(terminalStatus)) {
                throw new SagaProtocolException("ALREADY_TERMINAL requires a forward terminal status");
            }
            if ("SUCCEEDED".equals(terminalStatus)) {
                if (reasonCode != null) {
                    throw new SagaProtocolException("successful terminal outcome cannot carry a reason");
                }
            } else {
                SagaIds.requireReasonCode(reasonCode);
            }
        } else if ("CANCEL_CONFIRMED".equals(status)) {
            if (terminalStatus != null || reasonCode != null) {
                throw new SagaProtocolException("confirmed cancel cannot carry terminal data");
            }
        } else {
            if (terminalStatus != null) {
                throw new SagaProtocolException("terminal status is only valid for ALREADY_TERMINAL");
            }
            SagaIds.requireReasonCode(reasonCode);
        }
    }

    /**
     * 业务作用：表达取消屏障已经建立，后续 execute 必须被 gate 抑制。
     *
     * @return 取消确认
     */
    public static CancelOutcome confirmed() {
        return new CancelOutcome("CANCEL_CONFIRMED", null, null);
    }

    /**
     * 业务作用：表达正向效果已经有确定终态，取消不能伪造为屏障确认。
     *
     * @param terminalStatus SUCCEEDED、REJECTED 或 HALTED
     * @param reasonCode 可选原因码
     * @return 已有终态结果
     */
    public static CancelOutcome alreadyTerminal(String terminalStatus, String reasonCode) {
        return new CancelOutcome("ALREADY_TERMINAL", terminalStatus, reasonCode);
    }

    /**
     * 业务作用：表达外部效果仍可能迟到生效，阻止 Orchestrator 提前冻结补偿计划。
     *
     * @param reasonCode 稳定未决原因码
     * @return 未决结果
     */
    public static CancelOutcome resolutionPending(String reasonCode) {
        return new CancelOutcome("RESOLUTION_PENDING", null, reasonCode);
    }
}
