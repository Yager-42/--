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
import java.util.HashMap;
import java.util.HashSet;
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

        Set<Long> likedSet = Set.of();
        Set<Long> followedSet = Set.of();
        if (userId != null) {
            ReactionTargetVO template = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(0L)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            likedSet = reactionRepository.batchExists(template, userId, candidateIds);
            List<Long> authorIds = new ArrayList<>();
            for (FeedCardBaseVO base : baseMap.values()) {
                if (base != null && base.getAuthorId() != null) {
                    authorIds.add(base.getAuthorId());
                }
            }
            followedSet = relationQueryService.batchFollowing(userId, authorIds);
        }

        List<FeedItemVO> items = new ArrayList<>(Math.min(candidateIds.size(), normalizedLimit));
        for (Long postId : candidateIds) {
            FeedCardBaseVO base = baseMap.get(postId);
            if (base == null) {
                continue;
            }
            FeedCardStatVO stat = statMap.get(postId);
            boolean seen = false;
            if (userId != null) {
                try {
                    seen = feedFollowSeenRepository.isSeen(userId, postId);
                } catch (Exception ignored) {
                }
            }
            items.add(FeedItemVO.builder()
                    .postId(base.getPostId())
                    .authorId(base.getAuthorId())
                    .authorNickname(base.getAuthorNickname())
                    .authorAvatar(base.getAuthorAvatar())
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
        Map<Long, FeedCardBaseVO> baseMap = new HashMap<>(feedCardRepository.getBatch(candidateIds));
        List<Long> missIds = new ArrayList<>();
        for (Long id : candidateIds) {
            if (id != null && !baseMap.containsKey(id)) {
                missIds.add(id);
            }
        }
        if (missIds.isEmpty()) {
            return baseMap;
        }
        List<ContentPostEntity> posts = contentRepository.listPostsByIds(missIds);
        Map<Long, ContentPostEntity> postMap = new HashMap<>();
        Set<Long> authorIds = new HashSet<>();
        for (ContentPostEntity post : posts) {
            if (post == null || post.getPostId() == null) {
                continue;
            }
            postMap.put(post.getPostId(), post);
            if (post.getUserId() != null) {
                authorIds.add(post.getUserId());
            }
        }
        Map<Long, UserBriefVO> briefMap = new HashMap<>();
        for (UserBriefVO brief : userBaseRepository.listByUserIds(new ArrayList<>(authorIds))) {
            if (brief != null && brief.getUserId() != null) {
                briefMap.put(brief.getUserId(), brief);
            }
        }
        List<FeedCardBaseVO> toSave = new ArrayList<>();
        for (Long id : missIds) {
            ContentPostEntity post = postMap.get(id);
            if (post == null) {
                continue;
            }
            UserBriefVO brief = briefMap.get(post.getUserId());
            FeedCardBaseVO card = FeedCardBaseVO.builder()
                    .postId(post.getPostId())
                    .authorId(post.getUserId())
                    .authorNickname(brief == null ? null : brief.getNickname())
                    .authorAvatar(brief == null ? null : brief.getAvatarUrl())
                    .text(post.getContentText())
                    .summary(post.getSummary())
                    .mediaType(post.getMediaType())
                    .mediaInfo(post.getMediaInfo())
                    .publishTime(post.getCreateTime())
                    .build();
            baseMap.put(id, card);
            toSave.add(card);
        }
        feedCardRepository.saveBatch(toSave);
        return baseMap;
    }

    private Map<Long, FeedCardStatVO> loadStatCards(List<Long> candidateIds) {
        Map<Long, FeedCardStatVO> statMap = new HashMap<>(feedCardStatRepository.getBatch(candidateIds));
        List<FeedCardStatVO> toSave = new ArrayList<>();
        for (Long postId : candidateIds) {
            if (postId == null || statMap.containsKey(postId)) {
                continue;
            }
            ReactionTargetVO target = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(postId)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            FeedCardStatVO stat = FeedCardStatVO.builder()
                    .postId(postId)
                    .likeCount(reactionRepository.getCount(target))
                    .build();
            statMap.put(postId, stat);
            toSave.add(stat);
        }
        feedCardStatRepository.saveBatch(toSave);
        return statMap;
    }
}
