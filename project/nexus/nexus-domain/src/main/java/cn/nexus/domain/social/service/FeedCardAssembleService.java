package cn.nexus.domain.social.service;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedCardRepository;
import cn.nexus.domain.social.adapter.repository.IFeedFollowSeenRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.FeedCardBaseVO;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Feed 卡片组装服务。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-08
 */
@Service
@RequiredArgsConstructor
public class FeedCardAssembleService {

    private final IFeedCardRepository feedCardRepository;
    private final IObjectCounterService objectCounterService;
    private final IContentRepository contentRepository;
    private final IUserBaseRepository userBaseRepository;
    private final RelationQueryService relationQueryService;
    private final IFeedFollowSeenRepository feedFollowSeenRepository;

    /**
     * 批量组装 Feed 卡片。
     *
     * @param userId 当前用户 ID；匿名场景可为 `null`，类型：{@link Long}
     * @param source 卡片来源标记，类型：{@link String}
     * @param candidates 候选收件箱条目，类型：{@link List}&lt;{@link FeedInboxEntryVO}&gt;
     * @param normalizedLimit 归一化后的返回上限，类型：{@code int}
     * @return 可直接渲染的 Feed 卡片列表，类型：{@link List}&lt;{@link FeedItemVO}&gt;
     */
    public List<FeedItemVO> assemble(Long userId, String source, List<FeedInboxEntryVO> candidates, int normalizedLimit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Long> candidateIds = new ArrayList<>(candidates.size());
        for (FeedInboxEntryVO entry : candidates) {
            if (entry != null && entry.getPostId() != null) {
                candidateIds.add(entry.getPostId());
            }
        }
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        // 先批量拿稳定字段，避免对每个候选帖子重复查仓储。
        Map<Long, FeedCardBaseVO> baseMap = loadBaseCards(candidateIds);
        Map<Long, Long> likeCountMap = loadLikeCounts(candidateIds);
        Map<Long, UserBriefVO> authorMap = loadAuthorBriefs(baseMap.values());

        Set<Long> likedSet = Set.of();
        Set<Long> followedSet = Set.of();
        Set<Long> seenSet = Set.of();
        if (userId != null) {
            // 个性化状态只在登录用户场景计算；匿名流量直接跳过。
            likedSet = loadLikedSet(userId, candidateIds);
            followedSet = relationQueryService.batchFollowing(userId, new ArrayList<>(authorMap.keySet()));
            seenSet = feedFollowSeenRepository.batchSeen(userId, candidateIds);
        }

        List<FeedItemVO> items = new ArrayList<>(Math.min(candidateIds.size(), normalizedLimit));
        for (Long postId : candidateIds) {
            FeedCardBaseVO base = baseMap.get(postId);
            if (base == null) {
                continue;
            }
            Long likeCount = likeCountMap.get(postId);
            UserBriefVO author = authorMap.get(base.getAuthorId());
            boolean seen = userId != null && seenSet.contains(postId);
            items.add(FeedItemVO.builder()
                    .postId(base.getPostId())
                    .authorId(base.getAuthorId())
                    .authorNickname(author == null ? null : author.getNickname())
                    .authorAvatar(author == null ? null : author.getAvatarUrl())
                    .text(base.getText())
                    .summary(base.getSummary())
                    .mediaType(base.getMediaType())
                    .mediaInfo(base.getMediaInfo())
                    .publishTime(base.getPublishTime())
                    .source(source)
                    .likeCount(likeCount == null ? 0L : likeCount)
                    .liked(userId != null && likedSet.contains(postId))
                    .followed(userId != null && followedSet.contains(base.getAuthorId()))
                    .seen(seen)
                    .build());
            if (items.size() >= normalizedLimit) {
                break;
            }
        }
        return items;
    }

    private Map<Long, FeedCardBaseVO> loadBaseCards(List<Long> candidateIds) {
        return new HashMap<>(feedCardRepository.getOrLoadBatch(candidateIds, this::rebuildBaseCards));
    }

    private Map<Long, FeedCardBaseVO> rebuildBaseCards(List<Long> missIds) {
        if (missIds == null || missIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FeedCardBaseVO> rebuilt = new HashMap<>();
        List<ContentPostEntity> posts = contentRepository.listPostsByIds(missIds);
        Map<Long, ContentPostEntity> postMap = new HashMap<>();
        for (ContentPostEntity post : posts) {
            if (post != null && post.getPostId() != null) {
                postMap.put(post.getPostId(), post);
            }
        }
        for (Long id : missIds) {
            ContentPostEntity post = postMap.get(id);
            if (post == null) {
                continue;
            }
            FeedCardBaseVO card = FeedCardBaseVO.builder()
                    .postId(post.getPostId())
                    .authorId(post.getUserId())
                    .text(post.getContentText())
                    .summary(post.getSummary())
                    .mediaType(post.getMediaType())
                    .mediaInfo(post.getMediaInfo())
                    .publishTime(post.getCreateTime())
                    .build();
            rebuilt.put(id, card);
        }
        return rebuilt;
    }

    private Map<Long, Long> loadLikeCounts(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> likeCountMap = new HashMap<>(candidateIds.size());
        Map<Long, Map<String, Long>> countById = objectCounterService.getCountsBatch(
                ReactionTargetTypeEnumVO.POST,
                candidateIds,
                List.of(ObjectCounterType.LIKE));
        for (Long postId : candidateIds) {
            Map<String, Long> values = countById.get(postId);
            Long count = values == null ? 0L : values.get("like");
            likeCountMap.put(postId, count == null ? 0L : count);
        }
        return likeCountMap;
    }

    private Map<Long, UserBriefVO> loadAuthorBriefs(Collection<FeedCardBaseVO> cards) {
        Set<Long> authorIds = new LinkedHashSet<>();
        if (cards != null) {
            for (FeedCardBaseVO card : cards) {
                if (card != null && card.getAuthorId() != null) {
                    authorIds.add(card.getAuthorId());
                }
            }
        }
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserBriefVO> authorMap = new HashMap<>();
        for (UserBriefVO brief : userBaseRepository.listByUserIds(new ArrayList<>(authorIds))) {
            if (brief != null && brief.getUserId() != null) {
                authorMap.put(brief.getUserId(), brief);
            }
        }
        return authorMap;
    }

    private Set<Long> loadLikedSet(Long userId, List<Long> candidateIds) {
        if (userId == null || candidateIds == null || candidateIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> likedSet = new LinkedHashSet<>();
        for (Long postId : candidateIds) {
            if (postId == null) {
                continue;
            }
            if (objectCounterService.isLiked(ReactionTargetTypeEnumVO.POST, postId, userId)) {
                likedSet.add(postId);
            }
        }
        return likedSet;
    }

}
