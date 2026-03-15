package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
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
    private final IContentRepository contentRepository;

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
        ContentPostPageVO page = contentRepository.listUserPosts(followeeId, null, limit);
        List<ContentPostEntity> posts = page.getPosts() == null ? List.of() : page.getPosts();
        if (posts.isEmpty()) {
            return;
        }

        for (ContentPostEntity post : posts) {
            if (post == null || post.getPostId() == null || post.getCreateTime() == null) {
                continue;
            }
            feedTimelineRepository.addToInbox(followerId, post.getPostId(), post.getCreateTime());
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
        // 离线用户下次首页会走 rebuildIfNeeded；这里仅用于“立刻生效”体验。
        if (!feedTimelineRepository.inboxExists(followerId)) {
            return;
        }
        // 读侧会做关注/拉黑过滤，取消关注无需强制重建 inbox。
    }
}
