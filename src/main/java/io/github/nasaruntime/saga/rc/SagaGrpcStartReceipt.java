package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;
import io.github.nasaruntime.saga.SagaStartDisposition;
import io.github.nasaruntime.saga.SagaStartResult;

/**
 * Rust gRPC control plane 的 start 收据。
 */
public record SagaGrpcStartReceipt(
        SagaStartDisposition disposition,
        String requestDigest,
        SagaSnapshotView saga) implements SagaStartResult {

    /**
     * 业务作用：拒绝缺少 Rust 持久裁决或快照的 gRPC 成功响应。
     */
    public SagaGrpcStartReceipt {
        if (disposition == null || disposition == SagaStartDisposition.UNSPECIFIED || saga == null) {
            throw new SagaProtocolException("Saga gRPC start receipt is incomplete");
        }
        SagaIds.requireDigest(requestDigest);
    }
}
