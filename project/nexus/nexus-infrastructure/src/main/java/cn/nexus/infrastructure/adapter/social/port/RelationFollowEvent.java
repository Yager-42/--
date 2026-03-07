package cn.nexus.infrastructure.adapter.social.port;

/**
 * 关注事件。
 */
public record RelationFollowEvent(Long eventId, Long sourceId, Long targetId, String status) {
}
