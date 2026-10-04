package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaAuditPage;
import io.github.nasaruntime.saga.rc.SagaAuditRequest;
import io.github.nasaruntime.saga.rc.SagaQueryPage;
import io.github.nasaruntime.saga.rc.SagaQueryRequest;
import io.github.nasaruntime.saga.rc.SagaSnapshotView;
import io.github.nasaruntime.saga.rc.SagaStartRequest;

/**
 * 与 transport 无关的 Rust Saga control API。
 *
 * <p>实现只允许读取 Rust Orchestrator 的权威事实，不在 Java 建立全局 Saga 状态机。</p>
 */
public interface SagaControlPlane {

    /**
     * 业务作用：请求 Rust 原子创建或幂等命中一个 Saga 实例。
     *
     * @param request 固定业务身份和 definition 合同的 start 请求
     * @return Committed 或 Duplicate 的远端收据
     */
    SagaStartResult start(SagaStartRequest request);

    /**
     * 业务作用：读取 Rust 权威实例快照，不用 Java 本地缓存推断状态迁移。
     *
     * @param tenantId 租户身份
     * @param sagaId Saga 实例身份
     * @param traceparent 可选链路上下文
     * @return Rust 快照
     */
    SagaSnapshotView get(String tenantId, String sagaId, String traceparent);

    /**
     * 业务作用：按 Rust 的过滤、keyset 和 opaque token 合同读取实例页。
     *
     * @param request 查询条件
     * @param traceparent 可选链路上下文
     * @return Rust 实例页
     */
    SagaQueryPage query(SagaQueryRequest request, String traceparent);

    /**
     * 业务作用：读取 Rust 已提交审计事实，不在 Java 复制审计状态。
     *
     * @param request 审计目标与分页条件
     * @param traceparent 可选链路上下文
     * @return Rust 审计页
     */
    SagaAuditPage audit(SagaAuditRequest request, String traceparent);
}
