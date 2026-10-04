package io.github.nasaruntime.saga.rc;

import io.github.nasaruntime.saga.SagaProtocolException;

import java.util.List;

/**
 * Rust 实例查询页。
 *
 * @param sagas         当前页快照
 * @param nextPageToken 不透明下一页 token，可为空
 */
public record SagaQueryPage(List<SagaSnapshotView> sagas, String nextPageToken) {

    /**
     * 业务作用：冻结查询页，防止调用方修改已从 Rust 读取的事实集合。
     *
     * @param sagas         当前页快照，本地空引用按空集合处理
     * @param nextPageToken 远端 opaque token
     *                      返回：不可变页面，空 token 统一为 null；纯空白 token 抛出协议异常。
     */
    public SagaQueryPage {
        sagas = sagas == null ? List.of() : List.copyOf(sagas);
        nextPageToken = normalizePageToken(nextPageToken);
    }

    /**
     * 业务作用：保持远端分页 token 的原始身份，只统一没有后续页的空表示。
     *
     * @param token 远端返回的 opaque token
     * @return 没有后续页时为 null；非空 token 原样返回，纯空白 token 拒绝。
     */
    private static String normalizePageToken(String token) {
        if (token == null || token.isEmpty()) return null;
        if (token.isBlank()) throw new SagaProtocolException("Saga response page token is blank");
        return token;
    }
}
