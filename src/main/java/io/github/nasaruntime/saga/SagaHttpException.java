package io.github.nasaruntime.saga;

import java.util.Arrays;

/**
 * Rust managed HTTP 调用的脱敏失败边界。
 */
public final class SagaHttpException extends RuntimeException {

    private final SagaHttpDisposition disposition;
    private final int statusCode;
    private final byte[] responseBody;

    /**
     * 业务作用：保存 HTTP 失败的业务分类和有界响应正文，避免调用方把未知结果误判为确定失败。
     *
     * @param disposition 失败分类
     * @param statusCode HTTP 状态；没有收到响应时为 {@code -1}
     * @param responseBody 已按上限截断前的响应正文
     * @param message 不含密钥、正文业务数据和底层堆栈的描述
     * @param cause 底层异常，可为空
     */
    public SagaHttpException(
            SagaHttpDisposition disposition,
            int statusCode,
            byte[] responseBody,
            String message,
            Throwable cause) {
        super(message, cause);
        this.disposition = disposition;
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? new byte[0] : responseBody.clone();
    }

    /**
     * 业务作用：返回调用方选择退避、人工处理或原身份重试所需的分类。
     *
     * @return HTTP 失败分类
     */
    public SagaHttpDisposition disposition() {
        return disposition;
    }

    /**
     * 业务作用：暴露远端状态码，供指标和本地状态机记录，不依赖错误正文推断语义。
     *
     * @return HTTP 状态码；未收到响应时为 {@code -1}
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * 业务作用：提供有界错误正文供诊断适配器选择性记录摘要，禁止调用方把它当成稳定业务合同。
     *
     * @return 响应正文副本
     */
    public byte[] responseBody() {
        return Arrays.copyOf(responseBody, responseBody.length);
    }
}
