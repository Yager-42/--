package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedNegativeFeedbackRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Feed follow 补偿服务实现：在线用户刚关注后，立刻把“新关注的人”的最近 K 条内容写入 inbox。
 *
 * <p>注意：这是体验补偿，不是强一致性要求；离线用户不补偿，回归后走 inbox rebuild。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
@Service
@RequiredArgsConstructor
public class FeedFollowCompensationService implements IFeedFollowCompensationService {

    private final IFeedTimelineRepository feedTimelineRepository;
    private final IContentRepository contentRepository;
    private final IFeedNegativeFeedbackRepository feedNegativeFeedbackRepository;
    private final IFeedInboxRebuildService feedInboxRebuildService;

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

        Set<String> negativeTypes = feedNegativeFeedbackRepository.listPostTypes(followerId);
        for (ContentPostEntity post : posts) {
            if (post == null || post.getPostId() == null || post.getCreateTime() == null) {
                continue;
            }
            if (feedNegativeFeedbackRepository.contains(followerId, post.getPostId())) {
                continue;
            }
            if (hitNegativePostTypes(post, negativeTypes)) {
                continue;
            }
            feedTimelineRepository.addToInbox(followerId, post.getPostId(), post.getCreateTime());
        }
    }

    /**
     * 取消关注补偿：对在线用户强制重建 inbox，确保下一次刷新不再返回已取消关注者内容。
     *
     * <p>说明：当前 inbox 索引里没有 authorId，无法按作者做精确删除；强制重建是最简单可行的做法。</p>
     */
    @Override
    public void onUnfollow(Long followerId, Long followeeId) {
        if (followerId == null || followeeId == null || followerId.equals(followeeId)) {
            return;
        }
        // 离线用户下次首页会走 rebuildIfNeeded；这里只处理在线用户的“立刻生效”体验。
        if (!feedTimelineRepository.inboxExists(followerId)) {
            return;
        }
        feedInboxRebuildService.forceRebuild(followerId);
    }

    private boolean hitNegativePostTypes(ContentPostEntity post, Set<String> negativeTypes) {
        if (post == null || negativeTypes == null || negativeTypes.isEmpty()) {
            return false;
        }
        List<String> postTypes = post.getPostTypes();
        if (postTypes == null || postTypes.isEmpty()) {
            return false;
        }
        for (String postType : postTypes) {
            if (postType != null && negativeTypes.contains(postType)) {
                return true;
            }
        }
        return false;
    }
}
