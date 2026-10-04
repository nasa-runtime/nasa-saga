package io.github.nasaruntime.saga;

import io.github.nasaruntime.saga.rc.SagaAuditPage;
import io.github.nasaruntime.saga.rc.SagaAuditRequest;
import io.github.nasaruntime.saga.rc.SagaQueryPage;
import io.github.nasaruntime.saga.rc.SagaQueryRequest;
import io.github.nasaruntime.saga.rc.SagaSnapshotView;

/**
 * 读取响应与请求范围的共同合同，供 HTTP 和 gRPC 在各自的远端响应边界内调用。
 */
final class SagaReadResponse {

    /**
     * 业务作用：禁止实例化无状态的响应校验器。参数说明：无。返回：无实例对外发布。
     */
    private SagaReadResponse() {}

    /**
     * 业务作用：只交付本次读取指定的租户与实例，防止错配事实越过调用方的读取范围。
     *
     * @param snapshot 远端返回的完整快照
     * @param tenantId 请求租户
     * @param sagaId   请求实例
     * @return 身份一致的原快照；错配时抛出协议异常。
     */
    static SagaSnapshotView get(SagaSnapshotView snapshot, String tenantId, String sagaId) {
        // 远端成功码不能替代身份核对；不得把另一租户或实例的事实作为本次读取成功。
        if (!tenantId.equals(snapshot.tenantId()) || !sagaId.equals(snapshot.sagaId())) {
            throw new SagaProtocolException("Saga get response identity does not match request");
        }
        return snapshot;
    }

    /**
     * 业务作用：确认查询页的每条事实都落在请求声明的租户和过滤范围内。
     *
     * @param page    已完成字段和 token 校验的远端查询页
     * @param request 本次查询条件
     * @return 范围一致的原页面；缺失查询时间、过滤错配或超页时抛出协议异常。
     */
    static SagaQueryPage query(SagaQueryPage page, SagaQueryRequest request) {
        requirePageSize(page.sagas().size(), request.pageSize());
        for (SagaSnapshotView snapshot : page.sagas()) {
            // 查询投影必须携带时间证据；不能借用 start/get 的可空时间语义绕过范围复验。
            if (!request.tenantId().equals(snapshot.tenantId())
                    || request.workflow() != null && !request.workflow().equals(snapshot.workflow())
                    || !request.statuses().isEmpty() && !request.statuses().contains(snapshot.status())
                    || snapshot.createdAtMs() == null || snapshot.updatedAtMs() == null) {
                throw new SagaProtocolException("Saga query response does not match request scope");
            }
            // Rust 按微秒执行左闭右开过滤，再四舍五入投影为毫秒；合法记录可能等于毫秒上界，不能误拒绝。
            // 相同上下界本身是空区间；其余只拒绝毫秒投影足以证明越界的事实，亚毫秒裁决仍由 Rust 承担。
            if (request.createdFromMs() != null && snapshot.createdAtMs() < request.createdFromMs()
                    || request.createdToMs() != null && snapshot.createdAtMs() > request.createdToMs()
                    || request.createdFromMs() != null && request.createdFromMs().equals(request.createdToMs())) {
                throw new SagaProtocolException("Saga query response is outside requested time range");
            }
        }
        return page;
    }

    /**
     * 业务作用：保持单次审计读取的规模不超过调用方请求的页大小。
     *
     * @param page    已完成事实和 token 校验的远端审计页
     * @param request 本次审计查询条件
     * @return 未超页的原页面；超页时抛出协议异常。
     */
    static SagaAuditPage audit(SagaAuditPage page, SagaAuditRequest request) {
        requirePageSize(page.records().size(), request.pageSize());
        return page;
    }

    /**
     * 业务作用：统一两个 transport 的缺省分页规模。
     *
     * @param requested 已通过本地参数校验的可空页大小
     * @return 显式页大小或缺省值 100。
     */
    static int pageSize(Integer requested) {
        return requested == null ? 100 : requested;
    }

    /**
     * 业务作用：在远端响应阶段拒绝超过本次读取预算的页面。
     *
     * @param count     实际记录数
     * @param requested 请求页大小，可为空
     * @return 未超页时正常返回；超页时抛出协议异常。
     */
    private static void requirePageSize(int count, Integer requested) {
        if (count > pageSize(requested)) {
            throw new SagaProtocolException("Saga response exceeds requested page size");
        }
    }

    /**
     * 业务作用：确保下一页 token 能按原值用于后续请求，不把远端损坏延迟归因为本地输入错误。
     *
     * @param token 远端返回的 opaque token
     * @return null 或空字符串统一为 null；纯空白抛出协议异常，其余原样返回。
     */
    static String pageToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        // 非空 token 的字节属于远端分页合同，不能通过 trim 改写其身份。
        if (token.isBlank()) {
            throw new SagaProtocolException("Saga response page token is blank");
        }
        return token;
    }
}
