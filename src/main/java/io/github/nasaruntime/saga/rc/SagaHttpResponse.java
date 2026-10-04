package io.github.nasaruntime.saga.rc;

import java.util.List;
import java.util.Map;

/**
 * Java managed HTTP adapter 读取到的有界响应。
 *
 * @param statusCode HTTP 状态码
 * @param headers    响应头的只读视图
 * @param body       已通过大小门禁的原始响应正文
 */
public record SagaHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {

    /**
     * 业务作用：固定响应数据的可变边界，避免调用方修改后影响错误分类或 JSON 解码。
     */
    public SagaHttpResponse {
        if (headers == null || body == null) {
            throw new IllegalArgumentException("headers and body are required");
        }
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    /**
     * 业务作用：返回响应正文副本，保证 HTTP 适配器内部的读取结果不可被业务代码改写。
     *
     * @return 正文副本
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * 业务作用：按不区分大小写的 HTTP 头名称读取第一个值。
     *
     * @param name 头名称
     * @return 第一个头值，不存在时为 {@code null}
     */
    public String firstHeader(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
