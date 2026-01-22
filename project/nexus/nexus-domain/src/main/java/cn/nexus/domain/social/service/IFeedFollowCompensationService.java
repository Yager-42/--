package cn.nexus.domain.social.service;

/**
 * Feed follow 补偿服务：用于“刚关注后立刻能看到内容”的体验补偿。
 *
 * <p>只对在线用户（inbox key 已存在）执行回填；离线用户下次首页会走 inbox rebuild。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
public interface IFeedFollowCompensationService {

    /**
     * 处理关注事件：把被关注者最近一段内容回填到关注者 InboxTimeline。
     *
     * @param followerId 关注者用户 ID {@link Long}
     * @param followeeId 被关注者用户 ID {@link Long}
     */
    void onFollow(Long followerId, Long followeeId);

    /**
     * 处理取消关注事件：使关注者的 InboxTimeline 立刻反映最新关注图。
     *
     * @param followerId 关注者用户 ID {@link Long}
     * @param followeeId 被取消关注者用户 ID {@link Long}
     */
    void onUnfollow(Long followerId, Long followeeId);
}
