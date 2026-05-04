package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.config.FeedAuthorTimelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Feed AuthorTimeline 仓储 Redis 实现（ZSET）。
 *
 * <p>Key：feed:timeline:{authorId}，member=postId，score=publishTimeMs。</p>
 *
 * @author codex
 * @since 2026-05-04
 */
@Repository
@RequiredArgsConstructor
public class FeedAuthorTimelineRepository implements IFeedAuthorTimelineRepository {

    private static final String KEY_TIMELINE_PREFIX = "feed:timeline:";

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedAuthorTimelineProperties feedAuthorTimelineProperties;

    @Override
    public void addToTimeline(Long authorId, Long postId, Long publishTimeMs) {
        if (authorId == null || postId == null || publishTimeMs == null) {
            return;
        }
        String key = timelineKey(authorId);
        stringRedisTemplate.opsForZSet().add(key, postId.toString(), publishTimeMs.doubleValue());
        expireIfNeeded(key);
        trimToMaxSize(key);
    }

    @Override
    public void removeFromTimeline(Long authorId, Long postId) {
        if (authorId == null || postId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(timelineKey(authorId), postId.toString());
    }

    @Override
    public List<FeedInboxEntryVO> pageTimeline(Long authorId, Long cursorTimeMs, Long cursorPostId, int limit) {
        if (authorId == null) {
            return List.of();
        }
        int normalizedLimit = Math.max(1, limit);
        String key = timelineKey(authorId);
        stringRedisTemplate.expire(key, ttl());

        long safeCursorTime = cursorTimeMs == null ? Long.MAX_VALUE : cursorTimeMs;
        long safeCursorPostId = cursorPostId == null ? Long.MAX_VALUE : cursorPostId;
        int fetchCount = normalizedLimit + 20;

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

    @Override
    public boolean timelineExists(Long authorId) {
        if (authorId == null) {
            return false;
        }
        Boolean exists = stringRedisTemplate.hasKey(timelineKey(authorId));
        return Boolean.TRUE.equals(exists);
    }

    // ── private helpers ──────────────────────────────────────────────

    private String timelineKey(Long authorId) {
        return KEY_TIMELINE_PREFIX + authorId;
    }

    private Duration ttl() {
        int ttlDays = Math.max(1, feedAuthorTimelineProperties.getTtlDays());
        return Duration.ofDays(ttlDays);
    }

    private int maxSize() {
        return Math.max(1, feedAuthorTimelineProperties.getMaxSize());
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
        int maxSize = maxSize();
        Long size = stringRedisTemplate.opsForZSet().zCard(key);
        if (size == null || size <= maxSize) {
            return;
        }
        long removeCount = size - maxSize;
        stringRedisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
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
}
