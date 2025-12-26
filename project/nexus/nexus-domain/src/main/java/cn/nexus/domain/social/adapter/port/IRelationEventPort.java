package cn.nexus.domain.social.adapter.port;

/**
 * 关系事件发布端口。
 */
public interface IRelationEventPort {

    void onFollow(Long sourceId, Long targetId, String status);

    void onFriendEstablished(Long sourceId, Long targetId);

    void onBlock(Long sourceId, Long targetId);
}
