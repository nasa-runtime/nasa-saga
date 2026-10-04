package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaStartDisposition;

import io.github.nasaruntime.saga.proto.orchestrator.v1.SagaSnapshot;
import io.github.nasaruntime.saga.proto.orchestrator.v1.StartSagaResponse;

/**
 * Rust Orchestrator 返回的 start 持久收据。
 *
 * @param disposition   首次提交或幂等重复
 * @param requestDigest 远端计算的请求摘要
 * @param saga          已提交的 Saga 快照
 */
public record SagaStartReceipt(
        SagaStartDisposition disposition,
        String requestDigest,
        SagaSnapshot saga) {

    /**
     * 业务作用：把 generated gRPC 响应收窄为 Java 侧不暴露 enum 数值的领域收据。
     *
     * @param response Rust start 响应
     * @return Java start 收据
     */
    public static SagaStartReceipt fromProto(
            StartSagaResponse response) {
        SagaStartDisposition disposition = switch (response.getDisposition()) {
            case START_DISPOSITION_COMMITTED -> SagaStartDisposition.COMMITTED;
            case START_DISPOSITION_DUPLICATE -> SagaStartDisposition.DUPLICATE;
            default -> SagaStartDisposition.UNSPECIFIED;
        };
        return new SagaStartReceipt(disposition, response.getRequestDigest(), response.getSaga());
    }
}
