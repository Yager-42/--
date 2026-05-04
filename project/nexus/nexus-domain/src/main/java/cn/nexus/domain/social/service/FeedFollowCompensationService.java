package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Feed follow 补偿服务实现：在线用户刚关注后，立刻把“新关注的人”的最近 K 条内容写入 inbox。
 *
 * @author rr
 * @author codex
 * @since 2026-01-14
 */
@Service
@RequiredArgsConstructor
public class FeedFollowCompensationService implements IFeedFollowCompensationService {

    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedAuthorTimelineRepository feedAuthorTimelineRepository;

    /**
     * 刚关注后回填的“最近内容条数”（默认 20）。
     */
    @Value("${feed.follow.compensate.recentPosts:20}")
    private int recentPosts;

    /**
     * 处理关注事件：仅对在线用户执行回填。
     *
     * @param followerId 关注者用户 ID {@link Long}
     * @param followeeId 被关注者用户 ID {@link Long}
     */
    @Override
    public void onFollow(Long followerId, Long followeeId) {
        if (followerId == null || followeeId == null || followerId.equals(followeeId)) {
            return;
        }
        if (!feedTimelineRepository.inboxExists(followerId)) {
            return;
        }

        int limit = Math.max(1, recentPosts);
        List<FeedInboxEntryVO> entries = feedAuthorTimelineRepository.pageTimeline(followeeId, null, null, limit);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        for (FeedInboxEntryVO entry : entries) {
            if (entry == null || entry.getPostId() == null || entry.getPublishTimeMs() == null) {
                continue;
            }
            feedTimelineRepository.addToInbox(followerId, entry.getPostId(), entry.getPublishTimeMs());
        }
    }

    /**
     * 取消关注补偿：不再强制重建 inbox；由读侧过滤保证取消关注立刻生效。
     *
     * <p>说明：inbox 索引里没有 authorId，写侧做精确删除成本高；这里选择读侧过滤。</p>
     */
    @Override
    public void onUnfollow(Long followerId, Long followeeId) {
        if (followerId == null || followeeId == null || followerId.equals(followeeId)) {
            return;
        }
        // 读侧会做关注/拉黑过滤，取消关注无需强制重建 inbox。
    }
}
