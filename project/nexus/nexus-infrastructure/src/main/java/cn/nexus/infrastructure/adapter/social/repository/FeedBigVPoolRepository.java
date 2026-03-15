package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.config.FeedBigVPoolProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 大 V 聚合池仓储 Redis 实现（ZSET）。
 *
 * @author rr
 * @author codex
 * @since 2026-01-14
 */
@Repository
@RequiredArgsConstructor
public class FeedBigVPoolRepository implements IFeedBigVPoolRepository {

    private static final String KEY_POOL_PREFIX = "feed:bigv:pool:";

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedBigVPoolProperties feedBigVPoolProperties;

    /**
     * 执行 addToPool 逻辑。
     *
     * @param authorId authorId 参数。类型：{@link Long}
     * @param postId 帖子 ID。类型：{@link Long}
     * @param publishTimeMs publishTimeMs 参数。类型：{@link Long}
     */
    @Override
    public void addToPool(Long authorId, Long postId, Long publishTimeMs) {
        if (!feedBigVPoolProperties.isEnabled()) {
            return;
        }
        if (authorId == null || postId == null || publishTimeMs == null) {
            return;
        }
        String key = poolKey(bucketOf(authorId));
        stringRedisTemplate.opsForZSet().add(key, postId.toString(), publishTimeMs.doubleValue());
        expireIfNeeded(key);
        trimToMaxSize(key);
    }

    /**
     * 执行 removeFromPool 逻辑。
     *
     * @param authorId authorId 参数。类型：{@link Long}
     * @param postId 帖子 ID。类型：{@link Long}
     */
    @Override
    public void removeFromPool(Long authorId, Long postId) {
        if (!feedBigVPoolProperties.isEnabled()) {
            return;
        }
        if (authorId == null || postId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(poolKey(bucketOf(authorId)), postId.toString());
    }

    /**
     * 执行 pagePool 逻辑。
     *
     * @param bucket bucket 参数。类型：{@code int}
     * @param cursorTimeMs cursorTimeMs 参数。类型：{@link Long}
     * @param cursorPostId cursorPostId 参数。类型：{@link Long}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<FeedInboxEntryVO> pagePool(int bucket, Long cursorTimeMs, Long cursorPostId, int limit) {
        if (!feedBigVPoolProperties.isEnabled()) {
            return List.of();
        }
        int normalizedLimit = Math.max(1, limit);
        int safeBuckets = Math.max(1, feedBigVPoolProperties.getBuckets());
        int safeBucket = Math.floorMod(bucket, safeBuckets);
        String key = poolKey(safeBucket);
        expireIfNeeded(key);

        long safeCursorTime = cursorTimeMs == null ? Long.MAX_VALUE : cursorTimeMs;
        long safeCursorPostId = cursorPostId == null ? Long.MAX_VALUE : cursorPostId;
        int fetchCount = normalizedLimit + 20;

        // 先按时间分数粗拉一批，再用 (publishTimeMs, postId) 做二次游标过滤，补上同毫秒下的稳定翻页语义。
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0D, safeCursorTime, 0, fetchCount);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }

        List<FeedInboxEntryVO> result = new ArrayList<>(normalizedLimit);
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple == null || tuple.getValue() == null || tuple.getValue().isBlank() || tuple.getScore() == null) {
                continue;
            }
            Long postId = parseLong(tuple.getValue());
            if (postId == null) {
                continue;
            }
            long publishTimeMs = tuple.getScore().longValue();
            if (!passCursor(publishTimeMs, postId, safeCursorTime, safeCursorPostId)) {
                continue;
            }
            result.add(FeedInboxEntryVO.builder().postId(postId).publishTimeMs(publishTimeMs).build());
            if (result.size() >= normalizedLimit) {
                break;
            }
        }
        return result;
    }

    private int bucketOf(Long authorId) {
        int buckets = Math.max(1, feedBigVPoolProperties.getBuckets());
        return Math.floorMod(authorId == null ? 0L : authorId, buckets);
    }

    private boolean passCursor(long publishTimeMs, long postId, long cursorTimeMs, long cursorPostId) {
        if (cursorTimeMs == Long.MAX_VALUE && cursorPostId == Long.MAX_VALUE) {
            return true;
        }
        if (publishTimeMs < cursorTimeMs) {
            return true;
        }
        return publishTimeMs == cursorTimeMs && postId < cursorPostId;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String poolKey(int bucket) {
        return KEY_POOL_PREFIX + bucket;
    }

    private Duration ttl() {
        int ttlDays = Math.max(1, feedBigVPoolProperties.getTtlDays());
        return Duration.ofDays(ttlDays);
    }

    private void expireIfNeeded(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        Long ttlSeconds = stringRedisTemplate.getExpire(key);
        if (ttlSeconds == null || ttlSeconds < 0) {
            stringRedisTemplate.expire(key, ttl());
        }
    }

    private void trimToMaxSize(String key) {
        int maxSize = Math.max(1, feedBigVPoolProperties.getMaxSizePerBucket());
        Long size = stringRedisTemplate.opsForZSet().zCard(key);
        if (size == null || size <= maxSize) {
            return;
        }
        long removeCount = size - maxSize;
        stringRedisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
    }
}

