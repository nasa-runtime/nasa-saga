package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;
import io.github.nasaruntime.saga.SagaStartDisposition;
import io.github.nasaruntime.saga.SagaStartResult;

/**
 * Rust managed HTTP start 的远端收据。
 *
 * @param disposition   首次提交或幂等重复
 * @param requestDigest Rust 计算的 start 请求摘要
 * @param saga          Rust 返回的权威快照
 */
public record SagaHttpStartReceipt(
        SagaStartDisposition disposition,
        String requestDigest,
        SagaSnapshotView saga) implements SagaStartResult {

    /**
     * 业务作用：拒绝缺少远端裁决或实例快照的 HTTP 成功响应，避免把半截 JSON 当成已提交。
     */
    public SagaHttpStartReceipt {
        if (disposition == null || disposition == SagaStartDisposition.UNSPECIFIED || saga == null) {
            throw new SagaProtocolException("Saga HTTP start receipt is incomplete");
        }
        SagaIds.requireDigest(requestDigest);
    }
}
