package cn.nexus.domain.social.adapter.port;

/**
 * 内容发布后分发事件端口。
 */
public interface IContentDispatchPort {
    void onPublished(Long postId, Long userId);
}
