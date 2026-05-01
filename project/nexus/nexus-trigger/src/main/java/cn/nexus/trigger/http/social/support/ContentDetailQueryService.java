package cn.nexus.trigger.http.social.support;

import cn.nexus.api.social.content.dto.ContentDetailResponseDTO;
import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.infrastructure.support.SingleFlight;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 内容详情查询服务。
 *
 * <p>本地只缓存“稳定快照”，作者资料和点赞数这种动态字段每次现查，避免把旧展示值长期卡在本地缓存里。</p>
 *
 * @author m0_52354773
 * @author codex
 * @author {$authorName}
 * @since 2026-03-03
 */
@Slf4j
@Service
public class ContentDetailQueryService {

    private static final Duration LOCAL_TTL = Duration.ofHours(1);

    private final IContentRepository contentRepository;
    private final IUserBaseRepository userBaseRepository;
    private final IObjectCounterService objectCounterService;
    private final Executor aggregationExecutor;

    public ContentDetailQueryService(IContentRepository contentRepository,
                                    IUserBaseRepository userBaseRepository,
                                    IObjectCounterService objectCounterService,
                                    @Qualifier("aggregationExecutor") Executor aggregationExecutor) {
        this.contentRepository = contentRepository;
        this.userBaseRepository = userBaseRepository;
        this.objectCounterService = objectCounterService;
        this.aggregationExecutor = aggregationExecutor;
    }

    private final Cache<Long, StableSnapshot> localCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(LOCAL_TTL)
            .build();

    private final SingleFlight singleFlight = new SingleFlight();

    /**
     * 查询内容详情。
     *
     * @param postId 帖子 ID，类型： {@link Long}
     * @return 详情响应，类型： {@link ContentDetailResponseDTO}
     */
    public ContentDetailResponseDTO query(Long postId) {
        return query(postId, null);
    }

    /**
     * 查询内容详情。
     *
     * @param postId 帖子 ID，类型： {@link Long}
     * @param viewerUserId 当前用户 ID，匿名场景可为空，类型： {@link Long}
     * @return 详情响应，类型： {@link ContentDetailResponseDTO}
     */
    public ContentDetailResponseDTO query(Long postId, Long viewerUserId) {
        if (postId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "postId is required");
        }

        // 本地缓存只存稳定字段；命中后仍会在 `buildResponse` 阶段现查动态展示数据。
        StableSnapshot local = localCache.getIfPresent(postId);
        if (local != null) {
            return buildResponse(local, viewerUserId);
        }

        // `SingleFlight` 收口并发 miss，避免同一篇帖子被瞬时并发打爆主仓储。
        StableSnapshot snapshot = singleFlight.execute(detailQueryInflightKey(postId), () -> {
            StableSnapshot secondCheck = localCache.getIfPresent(postId);
            if (secondCheck != null) {
                return secondCheck;
            }

            ContentPostEntity post = contentRepository.findPost(postId);
            if (post == null || post.getPostId() == null) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }
            return StableSnapshot.builder()
                    .postId(post.getPostId())
                    .authorId(post.getUserId())
                    .title(post.getTitle())
                    .content(post.getContentText() == null ? "" : post.getContentText())
                    .summary(post.getSummary())
                    .summaryStatus(post.getSummaryStatus())
                    .mediaType(post.getMediaType())
                    .mediaInfo(post.getMediaInfo())
                    .locationInfo(post.getLocationInfo())
                    .status(post.getStatus())
                    .visibility(post.getVisibility())
                    .versionNum(post.getVersionNum())
                    .edited(post.getEdited())
                    .createTime(post.getCreateTime())
                    .build();
        });
        localCache.put(postId, snapshot);
        return buildResponse(snapshot, viewerUserId);
    }

    /**
     * 失效本地详情缓存。
     *
     * @param postId 帖子 ID，类型： {@link Long}
     */
    public void evictLocal(Long postId) {
        if (postId == null) {
            return;
        }
        localCache.invalidate(postId);
    }

    private UserBriefVO loadAuthor(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            List<UserBriefVO> list = userBaseRepository.listByUserIds(List.of(userId));
            if (list == null || list.isEmpty()) {
                return null;
            }
            return list.get(0);
        } catch (Exception e) {
            log.warn("load author failed, userId={}", userId, e);
            return null;
        }
    }

    private java.util.Map<String, Long> loadPostCounts(Long postId) {
        if (postId == null) {
            return java.util.Map.of("like", 0L, "fav", 0L);
        }
        try {
            java.util.Map<String, Long> counts = objectCounterService.getPostCounts(
                    postId,
                    java.util.List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));
            long like = counterValue(counts, ObjectCounterType.LIKE.getCode());
            long fav = counterValue(counts, ObjectCounterType.FAV.getCode());
            return java.util.Map.of(ObjectCounterType.LIKE.getCode(), like, ObjectCounterType.FAV.getCode(), fav);
        } catch (Exception e) {
            log.warn("load post counters failed, postId={}", postId, e);
            return java.util.Map.of("like", 0L, "fav", 0L);
        }
    }

    private String detailQueryInflightKey(Long postId) {
        return "detailQuery:" + postId;
    }

    private ContentDetailResponseDTO buildResponse(StableSnapshot snapshot, Long viewerUserId) {
        if (snapshot == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        // 昵称、头像、点赞数都是动态字段，不跟稳定快照一起长时间缓存。
        CompletableFuture<UserBriefVO> authorFuture = CompletableFuture.supplyAsync(
                () -> loadAuthor(snapshot.getAuthorId()),
                aggregationExecutor);
        CompletableFuture<java.util.Map<String, Long>> countsFuture = CompletableFuture.supplyAsync(
                () -> loadPostCounts(snapshot.getPostId()),
                aggregationExecutor);
        CompletableFuture<Boolean> likedFuture = CompletableFuture.supplyAsync(
                () -> viewerUserId != null && objectCounterService.isPostLiked(snapshot.getPostId(), viewerUserId),
                aggregationExecutor);
        CompletableFuture<Boolean> favedFuture = CompletableFuture.supplyAsync(
                () -> viewerUserId != null && objectCounterService.isPostFaved(snapshot.getPostId(), viewerUserId),
                aggregationExecutor);

        UserBriefVO author = authorFuture.join();
        java.util.Map<String, Long> counts = countsFuture.join();
        return ContentDetailResponseDTO.builder()
                .postId(snapshot.getPostId())
                .authorId(snapshot.getAuthorId())
                .authorNickname(author == null ? "" : safe(author.getNickname(), ""))
                .authorAvatarUrl(author == null ? "" : safe(author.getAvatarUrl(), ""))
                .title(snapshot.getTitle())
                .content(snapshot.getContent() == null ? "" : snapshot.getContent())
                .summary(snapshot.getSummary())
                .summaryStatus(snapshot.getSummaryStatus())
                .mediaType(snapshot.getMediaType())
                .mediaInfo(snapshot.getMediaInfo())
                .locationInfo(snapshot.getLocationInfo())
                .status(snapshot.getStatus())
                .visibility(snapshot.getVisibility())
                .versionNum(snapshot.getVersionNum())
                .edited(snapshot.getEdited())
                .createTime(snapshot.getCreateTime())
                .likeCount(counterValue(counts, ObjectCounterType.LIKE.getCode()))
                .favoriteCount(counterValue(counts, ObjectCounterType.FAV.getCode()))
                .liked(Boolean.TRUE.equals(likedFuture.join()))
                .faved(Boolean.TRUE.equals(favedFuture.join()))
                .build();
    }

    private long counterValue(java.util.Map<String, Long> counts, String code) {
        Long value = counts == null ? null : counts.get(code);
        return value == null ? 0L : Math.max(0L, value);
    }

    private String safe(String a, String fallback) {
        if (a == null || a.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return a;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class StableSnapshot {

        private Long postId;
        private Long authorId;
        private String title;
        private String content;
        private String summary;
        private Integer summaryStatus;
        private Integer mediaType;
        private String mediaInfo;
        private String locationInfo;
        private Integer status;
        private Integer visibility;
        private Integer versionNum;
        private Boolean edited;
        private Long createTime;
    }
}
