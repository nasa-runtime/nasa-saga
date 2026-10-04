package io.github.nasaruntime.saga.rc;

import java.util.Map;

/**
 * 宿主 Web 框架适配成的 HTTP participant 入站请求。
 *
 * @param method  HTTP method
 * @param path    宿主实际观察到的完整 path
 * @param headers 请求头
 * @param body    原始请求正文
 */
public record SagaHttpInboundRequest(String method, String path, Map<String, String> headers, byte[] body) {

    /**
     * 业务作用：冻结宿主传入的认证边界，确保验签和事件校验使用同一份正文。
     */
    public SagaHttpInboundRequest {
        if (method == null || path == null || headers == null || body == null) {
            throw new IllegalArgumentException("HTTP method, path, headers and body are required");
        }
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    /**
     * 业务作用：返回不可修改的原始 body 副本，防止异步 handler 改写后续审计依据。
     *
     * @return body 副本
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * 业务作用：按 HTTP 规则读取认证头，避免宿主框架大小写差异改变安全判断。
     *
     * @param name 头名称
     * @return 头值，不存在时为 {@code null}
     */
    public String header(String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
