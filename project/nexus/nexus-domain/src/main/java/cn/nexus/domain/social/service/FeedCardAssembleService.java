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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
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

    private final ConcurrentHashMap<String, CompletableFuture<Map<Long, FeedCardBaseVO>>> baseInflight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Map<Long, FeedCardStatVO>>> statInflight = new ConcurrentHashMap<>();

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
        if (userId != null) {
            ReactionTargetVO template = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(0L)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            likedSet = reactionRepository.batchExists(template, userId, candidateIds);
            followedSet = relationQueryService.batchFollowing(userId, new ArrayList<>(authorMap.keySet()));
        }

        List<FeedItemVO> items = new ArrayList<>(Math.min(candidateIds.size(), normalizedLimit));
        for (Long postId : candidateIds) {
            FeedCardBaseVO base = baseMap.get(postId);
            if (base == null) {
                continue;
            }
            FeedCardStatVO stat = statMap.get(postId);
            UserBriefVO author = authorMap.get(base.getAuthorId());
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
        Map<Long, FeedCardBaseVO> baseMap = new HashMap<>(feedCardRepository.getBatch(candidateIds));
        List<Long> missIds = collectMissIds(candidateIds, baseMap.keySet());
        if (missIds.isEmpty()) {
            return baseMap;
        }
        Map<Long, FeedCardBaseVO> rebuilt = executeSingleFlight(baseInflight, normalizeInflightKey(missIds), () -> rebuildBaseCards(missIds));
        baseMap.putAll(rebuilt);
        return baseMap;
    }

    private Map<Long, FeedCardStatVO> loadStatCards(List<Long> candidateIds) {
        Map<Long, FeedCardStatVO> statMap = new HashMap<>(feedCardStatRepository.getBatch(candidateIds));
        List<Long> missIds = collectMissIds(candidateIds, statMap.keySet());
        if (missIds.isEmpty()) {
            return statMap;
        }
        Map<Long, FeedCardStatVO> rebuilt = executeSingleFlight(statInflight, normalizeInflightKey(missIds), () -> rebuildStatCards(missIds));
        statMap.putAll(rebuilt);
        return statMap;
    }

    private Map<Long, FeedCardBaseVO> rebuildBaseCards(List<Long> missIds) {
        Map<Long, FeedCardBaseVO> rebuilt = new HashMap<>(feedCardRepository.getBatch(missIds));
        List<Long> unresolved = collectMissIds(missIds, rebuilt.keySet());
        if (unresolved.isEmpty()) {
            return rebuilt;
        }
        List<ContentPostEntity> posts = contentRepository.listPostsByIds(unresolved);
        Map<Long, ContentPostEntity> postMap = new HashMap<>();
        for (ContentPostEntity post : posts) {
            if (post != null && post.getPostId() != null) {
                postMap.put(post.getPostId(), post);
            }
        }
        List<FeedCardBaseVO> toSave = new ArrayList<>();
        for (Long id : unresolved) {
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
            toSave.add(card);
        }
        feedCardRepository.saveBatch(toSave);
        return rebuilt;
    }

    private Map<Long, FeedCardStatVO> rebuildStatCards(List<Long> missIds) {
        Map<Long, FeedCardStatVO> rebuilt = new HashMap<>(feedCardStatRepository.getBatch(missIds));
        List<Long> unresolved = collectMissIds(missIds, rebuilt.keySet());
        if (unresolved.isEmpty()) {
            return rebuilt;
        }
        List<FeedCardStatVO> toSave = new ArrayList<>();
        for (Long postId : unresolved) {
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
            toSave.add(stat);
        }
        feedCardStatRepository.saveBatch(toSave);
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

    private List<Long> collectMissIds(List<Long> ids, Set<Long> resolvedIds) {
        List<Long> missIds = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return missIds;
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || !seen.add(id) || (resolvedIds != null && resolvedIds.contains(id))) {
                continue;
            }
            missIds.add(id);
        }
        return missIds;
    }

    private String normalizeInflightKey(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        Set<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                normalized.add(id);
            }
        }
        return normalized.stream().sorted().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
    }

    private <T> T executeSingleFlight(ConcurrentHashMap<String, CompletableFuture<T>> inflight, String key, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        CompletableFuture<T> existing = inflight.putIfAbsent(key, future);
        if (existing != null) {
            return join(existing);
        }
        try {
            T value = supplier.get();
            future.complete(value);
            return value;
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            throw exception;
        } catch (Error error) {
            future.completeExceptionally(error);
            throw error;
        } finally {
            inflight.remove(key, future);
        }
    }

    private <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }
}
