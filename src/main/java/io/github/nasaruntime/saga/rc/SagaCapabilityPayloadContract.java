package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaIds;
import io.github.nasaruntime.saga.SagaProtocolException;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rust capability descriptor 使用的正文合同。
 *
 * @param contentType 小写规范媒体类型
 * @param schemaId    稳定 schema 身份；无 schema 的 JSON 使用空字符串
 */
public record SagaCapabilityPayloadContract(
        @JsonProperty("content_type") String contentType,
        @JsonProperty("schema_id") String schemaId) {

    /**
     * 业务作用：校验正文合同，使 capability 的路由选择不会改变业务字节的解释方式。
     */
    public SagaCapabilityPayloadContract {
        if (contentType == null || schemaId == null || SagaIds.utf8Length(contentType) > 128
                || !validContentType(contentType) || SagaIds.utf8Length(schemaId) > 256
                || !schemaId.equals(schemaId.strip()) || schemaId.chars().anyMatch(Character::isISOControl)
                || (!"application/json".equals(contentType) && schemaId.isEmpty())) {
            throw new SagaProtocolException("capability payload contract is invalid");
        }
    }

    /**
     * 业务作用：要求路由正文合同使用唯一媒体表示，避免不同副本解释不同正文。
     *
     * @param value 待核对的非空媒体类型
     * @return 仅规范的小写 type/subtype 成立，不接受媒体参数。
     */
    private static boolean validContentType(String value) {
        int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf('/', separator + 1) >= 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (index == separator) {
                continue;
            }
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && "!#$&^_.+-".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }
}
