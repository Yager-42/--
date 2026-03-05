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
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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

    private final Cache<Long, ContentDetailResponseDTO> localCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(LOCAL_TTL)
            .build();

    public ContentDetailResponseDTO query(Long postId) {
        if (postId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "postId 不能为空");
        }

        ContentDetailResponseDTO local = localCache.getIfPresent(postId);
        if (local != null) {
            return local;
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
            ContentDetailResponseDTO dto = parse(cached);
            if (dto != null) {
                localCache.put(postId, dto);
                return dto;
            }
        }

        ContentPostEntity post = contentRepository.findPostMeta(postId);
        if (post == null || post.getPostId() == null) {
            cacheNull(redisKey);
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }

        Long authorId = post.getUserId();
        CompletableFuture<UserBriefVO> authorFuture = CompletableFuture.supplyAsync(() -> loadAuthor(authorId));
        CompletableFuture<Long> likeFuture = CompletableFuture.supplyAsync(() -> loadLikeCount(postId));
        CompletableFuture<String> contentFuture = CompletableFuture.supplyAsync(() -> loadContent(post.getContentUuid()));

        UserBriefVO author = safeJoin(authorFuture);
        Long likeCount = safeJoin(likeFuture);
        String content = safeJoin(contentFuture);

        ContentDetailResponseDTO dto = ContentDetailResponseDTO.builder()
                .postId(post.getPostId())
                .authorId(authorId)
                .authorNickname(author == null ? "" : safe(author.getNickname(), ""))
                .authorAvatarUrl(author == null ? "" : safe(author.getAvatarUrl(), ""))
                .content(content == null ? "" : content)
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
                .likeCount(likeCount == null ? 0L : Math.max(0L, likeCount))
                .build();

        cacheValue(redisKey, dto);
        localCache.put(postId, dto);
        return dto;
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

    private void cacheValue(String redisKey, ContentDetailResponseDTO dto) {
        if (dto == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(dto);
            Duration ttl = jitter(REDIS_TTL);
            stringRedisTemplate.opsForValue().set(redisKey, json, ttl);
        } catch (Exception e) {
            log.warn("content detail redis set failed, key={}", redisKey, e);
        }
    }

    private ContentDetailResponseDTO parse(String json) {
        try {
            return objectMapper.readValue(json, ContentDetailResponseDTO.class);
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

    private <T> T safeJoin(CompletableFuture<T> f) {
        if (f == null) {
            return null;
        }
        try {
            return f.join();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safe(String a, String fallback) {
        if (a == null || a.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return a;
    }
}
