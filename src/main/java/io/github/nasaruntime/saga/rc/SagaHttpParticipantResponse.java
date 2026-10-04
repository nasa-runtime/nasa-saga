package io.github.nasaruntime.saga.rc;

import java.util.Map;

/**
 * Java participant HTTP ingress 的框架无关响应。
 *
 * @param statusCode HTTP 状态码
 * @param headers    响应头
 * @param body       UTF-8 JSON 或空正文
 */
public record SagaHttpParticipantResponse(int statusCode, Map<String, String> headers, byte[] body) {

    /**
     * 业务作用：冻结 participant 处理结果，避免宿主异步写响应时被修改。
     */
    public SagaHttpParticipantResponse {
        if (headers == null || body == null) {
            throw new IllegalArgumentException("headers and body are required");
        }
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    /**
     * 业务作用：返回 participant 响应正文副本，保证 Rust receipt 不被宿主改写。
     *
     * @return 正文副本
     */
    @Override
    public byte[] body() {
        return body.clone();
    }
}
