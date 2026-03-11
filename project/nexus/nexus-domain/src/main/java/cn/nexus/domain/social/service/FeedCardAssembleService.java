package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedCardRepository;
import cn.nexus.domain.social.adapter.repository.IFeedCardStatRepository;
import cn.nexus.domain.social.adapter.repository.IFeedFollowSeenRepository;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.FeedCardBaseVO;
import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
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

@Service
@RequiredArgsConstructor
public class FeedCardAssembleService {

    private final IFeedCardRepository feedCardRepository;
    private final IFeedCardStatRepository feedCardStatRepository;
    private final IContentRepository contentRepository;
    private final IUserBaseRepository userBaseRepository;
    private final IReactionRepository reactionRepository;
    private final RelationQueryService relationQueryService;
    private final IFeedFollowSeenRepository feedFollowSeenRepository;

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

        Map<Long, FeedCardBaseVO> baseMap = loadBaseCards(candidateIds);
        Map<Long, FeedCardStatVO> statMap = loadStatCards(candidateIds);
        Map<Long, UserBriefVO> authorMap = loadAuthorBriefs(baseMap.values());

        Set<Long> likedSet = Set.of();
        Set<Long> followedSet = Set.of();
        Set<Long> seenSet = Set.of();
        if (userId != null) {
            ReactionTargetVO template = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(0L)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            likedSet = reactionRepository.batchExists(template, userId, candidateIds);
            followedSet = relationQueryService.batchFollowing(userId, new ArrayList<>(authorMap.keySet()));
            seenSet = feedFollowSeenRepository.batchSeen(userId, candidateIds);
        }

        List<FeedItemVO> items = new ArrayList<>(Math.min(candidateIds.size(), normalizedLimit));
        for (Long postId : candidateIds) {
            FeedCardBaseVO base = baseMap.get(postId);
            if (base == null) {
                continue;
            }
            FeedCardStatVO stat = statMap.get(postId);
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
                    .likeCount(stat == null ? 0L : stat.getLikeCount())
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

    private Map<Long, FeedCardStatVO> loadStatCards(List<Long> candidateIds) {
        return new HashMap<>(feedCardStatRepository.getOrLoadBatch(candidateIds, this::rebuildStatCards));
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

    private Map<Long, FeedCardStatVO> rebuildStatCards(List<Long> missIds) {
        if (missIds == null || missIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FeedCardStatVO> rebuilt = new HashMap<>();
        for (Long postId : missIds) {
            ReactionTargetVO target = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(postId)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            FeedCardStatVO stat = FeedCardStatVO.builder()
                    .postId(postId)
                    .likeCount(reactionRepository.getCount(target))
                    .build();
            rebuilt.put(postId, stat);
        }
        return rebuilt;
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

}
