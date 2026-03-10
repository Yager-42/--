package cn.nexus.trigger.http.social.support;

import cn.nexus.api.social.content.dto.ContentDetailResponseDTO;
import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.infrastructure.support.SingleFlight;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentDetailQueryService {

    private static final String REDIS_KEY_PREFIX = "interact:content:detail:";
    private static final String REDIS_NULL = "NULL";

    private static final Duration LOCAL_TTL = Duration.ofHours(1);
    private static final Duration REDIS_TTL = Duration.ofDays(1);
    private static final Duration REDIS_NULL_TTL = Duration.ofSeconds(90);

    private final IContentRepository contentRepository;
    private final IUserBaseRepository userBaseRepository;
    private final IReactionCachePort reactionCachePort;
    private final IPostContentKvPort postContentKvPort;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private final Cache<Long, StableSnapshot> localCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(LOCAL_TTL)
            .build();

    private final SingleFlight singleFlight = new SingleFlight();

    public ContentDetailResponseDTO query(Long postId) {
        if (postId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "postId 不能为空");
        }

        StableSnapshot local = localCache.getIfPresent(postId);
        if (local != null) {
            return buildResponse(local);
        }

        String redisKey = redisKey(postId);
        String cached = null;
        try {
            cached = stringRedisTemplate.opsForValue().get(redisKey);
        } catch (Exception e) {
            log.warn("content detail redis get failed, key={}", redisKey, e);
        }
        if (REDIS_NULL.equals(cached)) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        if (cached != null && !cached.isBlank()) {
            StableSnapshot snapshot = parse(cached);
            if (snapshot != null) {
                localCache.put(postId, snapshot);
                return buildResponse(snapshot);
            }
            deleteRedisQuietly(redisKey);
        }

        StableSnapshot snapshot = singleFlight.execute(String.valueOf(postId), () -> rebuildSnapshot(postId, redisKey));
        localCache.put(postId, snapshot);
        return buildResponse(snapshot);
    }

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

    private Long loadLikeCount(Long postId) {
        if (postId == null) {
            return 0L;
        }
        try {
            ReactionTargetVO target = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(postId)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            return reactionCachePort.getCount(target);
        } catch (Exception e) {
            log.warn("load like count failed, postId={}", postId, e);
            return 0L;
        }
    }

    private String loadContent(String contentUuid) {
        if (contentUuid == null || contentUuid.isBlank()) {
            return "";
        }
        try {
            return postContentKvPort.find(contentUuid);
        } catch (Exception e) {
            log.warn("load content from kv failed, uuid={}", contentUuid, e);
            return "";
        }
    }

    private void cacheNull(String redisKey) {
        try {
            stringRedisTemplate.opsForValue().set(redisKey, REDIS_NULL, REDIS_NULL_TTL);
        } catch (Exception ignored) {
        }
    }

    private void cacheValue(String redisKey, StableSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            Duration ttl = jitter(REDIS_TTL);
            stringRedisTemplate.opsForValue().set(redisKey, json, ttl);
        } catch (Exception e) {
            log.warn("content detail redis set failed, key={}", redisKey, e);
        }
    }

    private StableSnapshot parse(String json) {
        try {
            return objectMapper.readValue(json, StableSnapshot.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String redisKey(Long postId) {
        return REDIS_KEY_PREFIX + postId;
    }

    private Duration jitter(Duration base) {
        if (base == null) {
            return Duration.ofSeconds(10);
        }
        long sec = Math.max(1L, base.getSeconds());
        long extra = ThreadLocalRandom.current().nextLong(0, Math.min(3600L, sec));
        return Duration.ofSeconds(sec + extra);
    }

    private StableSnapshot rebuildSnapshot(Long postId, String redisKey) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(redisKey);
            if (REDIS_NULL.equals(cached)) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }
            if (cached != null && !cached.isBlank()) {
                StableSnapshot snapshot = parse(cached);
                if (snapshot != null) {
                    return snapshot;
                }
                deleteRedisQuietly(redisKey);
            }
        } catch (AppException appException) {
            throw appException;
        } catch (Exception e) {
            log.warn("content detail redis second get failed, key={}", redisKey, e);
        }

        ContentPostEntity post = contentRepository.findPostMeta(postId);
        if (post == null || post.getPostId() == null) {
            cacheNull(redisKey);
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }

        StableSnapshot snapshot = StableSnapshot.builder()
                .postId(post.getPostId())
                .authorId(post.getUserId())
                .title(post.getTitle())
                .content(loadContent(post.getContentUuid()))
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
        cacheValue(redisKey, snapshot);
        return snapshot;
    }

    private ContentDetailResponseDTO buildResponse(StableSnapshot snapshot) {
        if (snapshot == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        UserBriefVO author = loadAuthor(snapshot.getAuthorId());
        Long likeCount = loadLikeCount(snapshot.getPostId());
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
                .likeCount(likeCount == null ? 0L : Math.max(0L, likeCount))
                .build();
    }

    private void deleteRedisQuietly(String redisKey) {
        try {
            stringRedisTemplate.delete(redisKey);
        } catch (Exception ignored) {
            // ignore
        }
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
