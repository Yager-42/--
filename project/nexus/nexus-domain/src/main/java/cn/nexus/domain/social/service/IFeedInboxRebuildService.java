package cn.nexus.domain.social.service;

/**
 * Feed Inbox 重建服务：负责离线用户回归时的 InboxTimeline 重建。
 *
 * <p>该服务只负责写入 inbox 缓存，不负责组装返回 DTO。</p>
 *
 * @author codex
 * @since 2026-01-13
 */
public interface IFeedInboxRebuildService {

    /**
     * 在需要时重建用户 InboxTimeline（只在 inbox key miss 场景触发）。
     *
     * @param userId 用户 ID {@link Long}
     */
    void rebuildIfNeeded(Long userId);
}
