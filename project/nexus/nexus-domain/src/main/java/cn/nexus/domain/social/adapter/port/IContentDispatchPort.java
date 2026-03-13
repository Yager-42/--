package cn.nexus.domain.social.adapter.port;

/**
 * 内容发布后分发事件端口。
 */
public interface IContentDispatchPort {
    void onPublished(Long postId, Long userId);

    /**
     * 内容更新事件（旁路，不允许影响主链路）。
     *
     * <p>用于搜索索引等“按 postId 回表再更新”的链路，避免索引陈旧。</p>
     *
     * @param postId      内容 ID
     * @param operatorId  操作者用户 ID
     */
    void onUpdated(Long postId, Long operatorId);

    /**
     * 内容删除/下架后分发事件（旁路，不允许影响主链路）。
     *
     * @param postId      内容 ID
     * @param operatorId  操作者用户 ID
     */
    void onDeleted(Long postId, Long operatorId);
}
