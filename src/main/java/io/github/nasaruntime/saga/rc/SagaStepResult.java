package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

/**
 * `@Saga` service 交给 participant transaction wrapper 的统一结果。
 *
 * @param status         当前 phase 的 Rust result status
 * @param terminalStatus cancel 的 {@code ALREADY_TERMINAL} 所携带的正向终态
 * @param reasonCode     稳定原因码
 */
public record SagaStepResult(String status, String terminalStatus, String reasonCode) {

    /**
     * 业务作用：冻结业务结论并限制状态字段只能进入 Rust result envelope 的封闭集合。
     */
    public SagaStepResult {
        if (status == null) {
            throw new SagaProtocolException("Saga step result status is required");
        }
        switch (status) {
            case "SUCCEEDED" -> {
                if (terminalStatus != null || reasonCode != null) {
                    throw new SagaProtocolException("successful result cannot carry terminal data or a reason");
                }
            }
            case "REJECTED", "UNKNOWN", "HALTED" -> {
                if (terminalStatus != null) {
                    throw new SagaProtocolException("forward result cannot carry a terminal status");
                }
                SagaIds.requireReasonCode(reasonCode);
            }
            case "CANCEL_CONFIRMED" -> {
                if (terminalStatus != null || reasonCode != null) {
                    throw new SagaProtocolException("confirmed cancel cannot carry terminal data");
                }
            }
            case "RESOLUTION_PENDING" -> {
                if (terminalStatus != null) {
                    throw new SagaProtocolException("pending cancel cannot carry a terminal status");
                }
                SagaIds.requireReasonCode(reasonCode);
            }
            case "ALREADY_TERMINAL" -> {
                if (!"SUCCEEDED".equals(terminalStatus) && !"REJECTED".equals(terminalStatus)
                        && !"HALTED".equals(terminalStatus)) {
                    throw new SagaProtocolException("already-terminal result requires a forward status");
                }
                if ("SUCCEEDED".equals(terminalStatus)) {
                    if (reasonCode != null) {
                        throw new SagaProtocolException("successful terminal result cannot carry a reason");
                    }
                } else {
                    SagaIds.requireReasonCode(reasonCode);
                }
            }
            default -> throw new SagaProtocolException("Saga step result status is invalid");
        }
    }

    /**
     * 业务作用：把正向 execute/resolve 结论投影为 participant result 状态。
     *
     * @param outcome service 返回的正向结论
     * @return Rust 兼容的结果
     */
    public static SagaStepResult from(SagaOutcome outcome) {
        if (outcome == null) {
            throw new SagaProtocolException("Saga outcome is required");
        }
        return new SagaStepResult(outcome.status(), null, outcome.reasonCode());
    }

    /**
     * 业务作用：把补偿结论投影为 Rust result 状态，保留补偿没有 REJECTED 分支的不变量。
     *
     * @param outcome service 返回的补偿结论
     * @return Rust 兼容的结果
     */
    public static SagaStepResult from(CompensationOutcome outcome) {
        if (outcome == null) {
            throw new SagaProtocolException("compensation outcome is required");
        }
        return new SagaStepResult(outcome.status(), null, outcome.reasonCode());
    }

    /**
     * 业务作用：把外部取消裁决投影为 Rust 三分支 cancel result。
     *
     * @param outcome service 返回的取消裁决
     * @return Rust 兼容的结果
     */
    public static SagaStepResult from(CancelOutcome outcome) {
        if (outcome == null) {
            throw new SagaProtocolException("cancel outcome is required");
        }
        return new SagaStepResult(outcome.status(), outcome.terminalStatus(), outcome.reasonCode());
    }

    /**
     * 业务作用：构造 local-fenceable 取消由 gate 确认后的结果。
     *
     * @return 取消确认结果
     */
    public static SagaStepResult cancelConfirmed() {
        return new SagaStepResult("CANCEL_CONFIRMED", null, null);
    }

    /**
     * 业务作用：表达 local step 返回未知结果时 Rust wrapper 的冻结行为。
     *
     * @param reasonCode 稳定冻结原因码
     * @return HALTED 结果
     */
    public static SagaStepResult halted(String reasonCode) {
        return new SagaStepResult("HALTED", null, reasonCode);
    }
}
