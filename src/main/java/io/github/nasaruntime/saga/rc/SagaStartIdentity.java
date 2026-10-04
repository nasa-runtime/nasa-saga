package io.github.nasaruntime.saga.rc;

/**
 * 发起意图要求远端成功快照保持的业务身份，供 direct 与 reliable 共用。
 *
 * @param tenantId                 发起租户
 * @param sagaId                   本次发起固定的 Saga 身份
 * @param workflow                 发起 workflow
 * @param definitionVersion        发起定义版本
 * @param expectedDefinitionDigest 可选的定义摘要前置条件
 * @param businessKey              本次发起的业务槽位
 */
public record SagaStartIdentity(String tenantId, String sagaId, String workflow, int definitionVersion,
                                String expectedDefinitionDigest, String businessKey) {

    /**
     * 业务作用：冻结 direct start 要求的返回身份，防止完整收据被误认成本次发起的成功。
     *
     * @param request 由发送入口完成本地合同校验的发起请求
     * @return 对应请求的身份条件，不计算或替换 Rust 的请求摘要。
     */
    public static SagaStartIdentity from(SagaStartRequest request) {
        return new SagaStartIdentity(request.tenantId(), request.sagaId(), request.workflow(),
                request.definitionVersion(), request.expectedDefinitionDigest(), request.businessKey());
    }

    /**
     * 业务作用：复验 Committed 与 Duplicate 均属于原租户、实例、业务槽位和定义。
     *
     * @param snapshot 已完成响应字段校验的 Rust 快照
     * @return 固定身份全部一致且满足可选定义摘要前置条件时为 true，缺失或错配时为 false。
     */
    public boolean matches(SagaSnapshotView snapshot) {
        // Duplicate 可以命中已有业务槽位；没有身份一致的证据就不能把另一实例确认成本次发起成功。
        return snapshot != null && tenantId.equals(snapshot.tenantId()) && sagaId.equals(snapshot.sagaId())
                && workflow.equals(snapshot.workflow()) && businessKey.equals(snapshot.businessKey())
                && definitionVersion == snapshot.definitionVersion()
                && (expectedDefinitionDigest == null || expectedDefinitionDigest.isEmpty()
                || expectedDefinitionDigest.equals(snapshot.definitionDigest()));
    }
}
