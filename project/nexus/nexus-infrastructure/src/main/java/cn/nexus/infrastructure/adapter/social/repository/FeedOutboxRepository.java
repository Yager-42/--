package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.config.FeedOutboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Feed Outbox 仓储 Redis 实现（ZSET）。
 *
 * <p>Key：feed:outbox:{authorId}</p>
 *
 * @author codex
 * @since 2026-01-14
 */
@Repository
@RequiredArgsConstructor
public class FeedOutboxRepository implements IFeedOutboxRepository {

    private static final String KEY_OUTBOX_PREFIX = "feed:outbox:";
    private static final String KEY_OUTBOX_TMP_PREFIX = "feed:outbox:tmp:";

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedOutboxProperties feedOutboxProperties;

    @Override
    public void addToOutbox(Long authorId, Long postId, Long publishTimeMs) {
        if (authorId == null || postId == null || publishTimeMs == null) {
            return;
        }
        String key = outboxKey(authorId);
        stringRedisTemplate.opsForZSet().add(key, postId.toString(), publishTimeMs.doubleValue());
        stringRedisTemplate.expire(key, ttl());
        trimToMaxSize(key);
    }

    @Override
    public void removeFromOutbox(Long authorId, Long postId) {
        if (authorId == null || postId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(outboxKey(authorId), postId.toString());
    }

    @Override
    public void replaceOutbox(Long authorId, List<FeedInboxEntryVO> entries) {
        if (authorId == null) {
            return;
        }
        String outboxKey = outboxKey(authorId);
        if (entries == null || entries.isEmpty()) {
            stringRedisTemplate.delete(outboxKey);
            return;
        }

        String tmpKey = outboxTmpKey(authorId, System.currentTimeMillis());
        for (FeedInboxEntryVO entry : entries) {
            if (entry == null || entry.getPostId() == null || entry.getPublishTimeMs() == null) {
                continue;
            }
            stringRedisTemplate.opsForZSet()
                    .add(tmpKey, entry.getPostId().toString(), entry.getPublishTimeMs().doubleValue());
        }

        stringRedisTemplate.expire(tmpKey, ttl());
        stringRedisTemplate.rename(tmpKey, outboxKey);
        stringRedisTemplate.expire(outboxKey, ttl());
        trimToMaxSize(outboxKey);
    }

    @Override
    public List<FeedInboxEntryVO> pageOutbox(Long authorId, Long cursorTimeMs, Long cursorPostId, int limit) {
        if (authorId == null) {
            return List.of();
        }
        int normalizedLimit = Math.max(1, limit);
        String key = outboxKey(authorId);
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
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String outboxKey(Long authorId) {
        return KEY_OUTBOX_PREFIX + authorId;
    }

    private String outboxTmpKey(Long authorId, long epochMs) {
        return KEY_OUTBOX_TMP_PREFIX + authorId + ":" + epochMs;
    }

    private Duration ttl() {
        int ttlDays = Math.max(1, feedOutboxProperties.getTtlDays());
        return Duration.ofDays(ttlDays);
    }

    private void trimToMaxSize(String key) {
        int maxSize = Math.max(1, feedOutboxProperties.getMaxSize());
        Long size = stringRedisTemplate.opsForZSet().zCard(key);
        if (size == null || size <= maxSize) {
            return;
        }
        long removeCount = size - maxSize;
        stringRedisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
    }
}

