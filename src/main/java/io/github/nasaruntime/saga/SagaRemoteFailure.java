package io.github.nasaruntime.saga;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import java.util.Locale;

/**
 * HTTP 与 gRPC 共用的远端失败语义；不携带远端正文、凭据或异常描述。
 */
public enum SagaRemoteFailure {
    UNAUTHENTICATED(401, true),
    PERMISSION_DENIED(403, true),
    NOT_FOUND(404, true),
    ALREADY_EXISTS(409, true),
    FAILED_PRECONDITION(409, true),
    INVALID_ARGUMENT(422, true),
    UNIMPLEMENTED(422, true),
    UNCONFIRMED(503, false);

    private final int httpStatus;
    private final boolean deterministic;

    /**
     * 业务作用：固定业务响应与自动重投策略，避免各传输适配器作出不同裁决。
     *
     * @param httpStatus    对外业务 facade 的 HTTP 状态
     * @param deterministic 是否已有明确拒绝证据
     *                      返回：不包含原始异常内容的分类。
     */
    SagaRemoteFailure(int httpStatus, boolean deterministic) {
        this.httpStatus = httpStatus;
        this.deterministic = deterministic;
    }

    /**
     * 业务作用：把已知远端拒绝归一为业务分类，证据不足时保留原身份等待查询或重投。
     *
     * @param failure HTTP 或 gRPC 调用抛出的异常
     * @return 确定性拒绝或 UNCONFIRMED；不推断瞬态失败意味着远端未提交。
     */
    public static SagaRemoteFailure classify(Throwable failure) {
        if (failure instanceof SagaHttpException http) {
            // 无确定拒绝证据不能停止投递，超时与丢失收据可能发生在远端提交之后。
            if (http.disposition() != SagaHttpDisposition.DETERMINISTIC_REJECT) return UNCONFIRMED;
            return switch (http.statusCode()) {
                case 401 -> UNAUTHENTICATED;
                case 403 -> PERMISSION_DENIED;
                case 404 -> NOT_FOUND;
                case 409 -> ALREADY_EXISTS;
                case 412 -> FAILED_PRECONDITION;
                case 405, 501 -> UNIMPLEMENTED;
                default -> INVALID_ARGUMENT;
            };
        }
        Status status = failure instanceof StatusRuntimeException grpc ? grpc.getStatus()
                : failure instanceof StatusException grpc ? grpc.getStatus() : null;
        if (status == null) return UNCONFIRMED;
        return switch (status.getCode()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED;
            case PERMISSION_DENIED -> PERMISSION_DENIED;
            case NOT_FOUND -> NOT_FOUND;
            case ALREADY_EXISTS -> ALREADY_EXISTS;
            case FAILED_PRECONDITION -> FAILED_PRECONDITION;
            case INVALID_ARGUMENT -> INVALID_ARGUMENT;
            case UNIMPLEMENTED -> UNIMPLEMENTED;
            default -> UNCONFIRMED;
        };
    }

    /**
     * 业务作用：为业务 facade 保留认证、授权、缺失、冲突与合同错误的区分。
     * 参数说明：无。
     *
     * @return 业务 HTTP 状态；证据不足统一为 503。
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 业务作用：决定冻结意图是否需要人工处置，避免确定拒绝无限重投。
     * 参数说明：无。
     *
     * @return 明确拒绝时为 true；false 不代表远端未执行。
     */
    public boolean deterministic() {
        return deterministic;
    }

    /**
     * 业务作用：提供可持久化且不会泄漏远端异常内容的稳定原因码。
     * 参数说明：无。
     *
     * @return 仅由固定分类名生成的低敏原因码。
     */
    public String errorCode() {
        return "remote_" + name().toLowerCase(Locale.ROOT);
    }
}
