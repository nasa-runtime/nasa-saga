package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

/**
 * compensate 的可提交结果，对应 Rust `CompensationOutcome`。
 *
 * @param status SUCCEEDED、UNKNOWN 或 HALTED
 * @param reasonCode 非成功状态的稳定原因码
 */
public record CompensationOutcome(String status, String reasonCode) {

    /**
     * 业务作用：限制补偿结果不出现 Rust 合同不存在的 REJECTED 分支。
     */
    public CompensationOutcome {
        if (!"SUCCEEDED".equals(status) && !"UNKNOWN".equals(status) && !"HALTED".equals(status)) {
            throw new SagaProtocolException("compensation outcome status is invalid");
        }
        if ("SUCCEEDED".equals(status)) {
            if (reasonCode != null) {
                throw new SagaProtocolException("successful compensation cannot carry a reason");
            }
        } else {
            SagaIds.requireReasonCode(reasonCode);
        }
    }

    /**
     * 业务作用：表达补偿完成或幂等重入已经确认完成。
     *
     * @return 成功结果
     */
    public static CompensationOutcome succeeded() {
        return new CompensationOutcome("SUCCEEDED", null);
    }

    /**
     * 业务作用：表达补偿外部效果仍未知，保持原效果身份等待解决。
     *
     * @param reasonCode 稳定未知原因码
     * @return 未知结果
     */
    public static CompensationOutcome unknown(String reasonCode) {
        return new CompensationOutcome("UNKNOWN", reasonCode);
    }

    /**
     * 业务作用：表达补偿不能安全完成，阻止系统把未完成补偿误记为成功。
     *
     * @param reasonCode 稳定冻结原因码
     * @return 冻结结果
     */
    public static CompensationOutcome halted(String reasonCode) {
        return new CompensationOutcome("HALTED", reasonCode);
    }
}
