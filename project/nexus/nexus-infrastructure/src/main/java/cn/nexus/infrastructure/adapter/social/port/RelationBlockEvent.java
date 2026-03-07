package cn.nexus.infrastructure.adapter.social.port;

/**
 * 屏蔽事件。
 */
public record RelationBlockEvent(Long eventId, Long sourceId, Long targetId) {
}
