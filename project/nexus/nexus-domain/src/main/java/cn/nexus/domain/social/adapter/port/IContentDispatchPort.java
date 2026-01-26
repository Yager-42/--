package cn.nexus.domain.social.adapter.port;

/**
 * 内容发布后分发事件端口。
 */
public interface IContentDispatchPort {
    void onPublished(Long postId, Long userId);

    /**
     * 内容删除/下架后分发事件（旁路，不允许影响主链路）。
     *
     * @param postId      内容 ID
     * @param operatorId  操作者用户 ID
     */
    void onDeleted(Long postId, Long operatorId);
}
