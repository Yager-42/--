package cn.nexus.infrastructure.adapter.social.port;

/**
 * 好友建立事件。
 */
public record RelationFriendEvent(Long sourceId, Long targetId) {
}
