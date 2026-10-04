package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaSnapshotView;

/**
 * transport 无关的 Rust Saga start 远端收据。
 */
public interface SagaStartResult {

    /**
     * 业务作用：读取 Rust 对本次 start 的持久裁决。
     *
     * @return Committed 或 Duplicate
     */
    SagaStartDisposition disposition();

    /**
     * 业务作用：读取 Rust 计算的 start request digest，供跨协议审计和冲突复核。
     *
     * @return Rust request digest
     */
    String requestDigest();

    /**
     * 业务作用：读取 Rust Orchestrator 提供的权威实例快照。
     *
     * @return 实例快照
     */
    SagaSnapshotView saga();
}
