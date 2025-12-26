package cn.nexus.infrastructure.adapter.social.port;

/**
 * 屏蔽事件。
 */
public record RelationBlockEvent(Long sourceId, Long targetId) {
}
