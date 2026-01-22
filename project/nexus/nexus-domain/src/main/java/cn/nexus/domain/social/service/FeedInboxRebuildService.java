package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedNegativeFeedbackRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Feed Inbox 重建服务实现：离线拉（inbox miss）时，按关注列表回填最近一段时间的内容。
 *
 * @author codex
 * @since 2026-01-13
 */
@Service
@RequiredArgsConstructor
public class FeedInboxRebuildService implements IFeedInboxRebuildService {

    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IContentRepository contentRepository;
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedNegativeFeedbackRepository feedNegativeFeedbackRepository;

    /**
     * 每个关注对象拉取的最近内容条数（默认 20）。 {@code int}
     */
    @Value("${feed.rebuild.perFollowingLimit:20}")
    private int perFollowingLimit;

    /**
     * 重建后写入 inbox 的条数上限（默认 200）。 {@code int}
     */
    @Value("${feed.rebuild.inboxSize:200}")
    private int inboxSize;

    /**
     * 重建时最多扫描的关注对象数量（默认 2000）。 {@code int}
     */
    @Value("${feed.rebuild.maxFollowings:2000}")
    private int maxFollowings;

    /**
     * 在需要时重建用户 InboxTimeline。
     *
     * <p>触发条件由上游保证：通常只在 timeline 首页（cursor 为空）且 inbox key miss 时调用。</p>
     *
     * @param userId 用户 ID {@link Long}
     */
    @Override
    public void rebuildIfNeeded(Long userId) {
        if (userId == null) {
            return;
        }
        if (feedTimelineRepository.inboxExists(userId)) {
            return;
        }
        doRebuild(userId);
    }

    @Override
    public void forceRebuild(Long userId) {
        if (userId == null) {
            return;
        }
        doRebuild(userId);
    }

    private void doRebuild(Long userId) {
        List<Long> targets = buildRebuildTargets(userId);
        if (targets.isEmpty()) {
            feedTimelineRepository.replaceInbox(userId, List.of());
            return;
        }

        List<ContentPostEntity> candidates = collectRecentPosts(targets);
        if (candidates.isEmpty()) {
            feedTimelineRepository.replaceInbox(userId, List.of());
            return;
        }

        candidates.sort(postComparator());
        List<FeedInboxEntryVO> entries = buildInboxEntries(userId, candidates);
        feedTimelineRepository.replaceInbox(userId, entries);
    }

    private List<Long> buildRebuildTargets(Long userId) {
        List<Long> targets = new ArrayList<>();
        targets.add(userId);

        int limit = Math.max(0, maxFollowings);
        List<Long> followings = relationAdjacencyCachePort.listFollowing(userId, limit);
        if (followings == null || followings.isEmpty()) {
            return targets;
        }

        for (Long targetId : followings) {
            if (targetId == null || targetId.equals(userId)) {
                continue;
            }
            targets.add(targetId);
        }
        return targets;
    }

    private List<ContentPostEntity> collectRecentPosts(List<Long> targets) {
        List<ContentPostEntity> candidates = new ArrayList<>();
        int limit = Math.max(1, perFollowingLimit);
        for (Long targetId : targets) {
            if (targetId == null) {
                continue;
            }
            ContentPostPageVO page = contentRepository.listUserPosts(targetId, null, limit);
            List<ContentPostEntity> posts = page.getPosts() == null ? List.of() : page.getPosts();
            if (!posts.isEmpty()) {
                candidates.addAll(posts);
            }
        }
        return candidates;
    }

    private Comparator<ContentPostEntity> postComparator() {
        return (a, b) -> {
            if (a == b) {
                return 0;
            }
            if (a == null) {
                return 1;
            }
            if (b == null) {
                return -1;
            }
            Long ta = a.getCreateTime();
            Long tb = b.getCreateTime();
            int timeCompare = compareDesc(ta, tb);
            if (timeCompare != 0) {
                return timeCompare;
            }
            return compareDesc(a.getPostId(), b.getPostId());
        };
    }

    private int compareDesc(Long a, Long b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return Long.compare(b, a);
    }

    private List<FeedInboxEntryVO> buildInboxEntries(Long userId, List<ContentPostEntity> candidates) {
        int limit = Math.max(1, inboxSize);
        List<FeedInboxEntryVO> entries = new ArrayList<>(limit);
        Set<String> negativeTypes = feedNegativeFeedbackRepository.listPostTypes(userId);
        for (ContentPostEntity post : candidates) {
            if (post == null) {
                continue;
            }
            Long postId = post.getPostId();
            Long publishTimeMs = post.getCreateTime();
            if (postId == null || publishTimeMs == null) {
                continue;
            }
            if (feedNegativeFeedbackRepository.contains(userId, postId)) {
                continue;
            }
            if (hitNegativePostTypes(post, negativeTypes)) {
                continue;
            }
            entries.add(FeedInboxEntryVO.builder()
                    .postId(postId)
                    .publishTimeMs(publishTimeMs)
                    .build());
            if (entries.size() >= limit) {
                break;
            }
        }
        return entries;
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
