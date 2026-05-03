package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.config.FeedOutboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
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
    private static final String KEY_OUTBOX_REBUILD_LOCK_PREFIX = "feed:outbox:rebuild:lock:";
    private static final RedisScript<Long> REPLACE_OUTBOX_SCRIPT = new DefaultRedisScript<>("""
            local realKey = ARGV[1]
            local tmpKey = ARGV[2]
            local ttlSeconds = tonumber(ARGV[3])
            local maxSize = tonumber(ARGV[4])
            local windowSize = tonumber(ARGV[5])
            local entryCount = tonumber(ARGV[6])

            redis.call('DEL', tmpKey)
            if entryCount == 0 then
                redis.call('DEL', realKey)
                return 1
            end

            local argIndex = 7
            for i = 1, entryCount do
                redis.call('ZADD', tmpKey, ARGV[argIndex + 1], ARGV[argIndex])
                argIndex = argIndex + 2
            end

            if windowSize > 0 then
                local oldMembers = redis.call('ZREVRANGE', realKey, 0, windowSize - 1, 'WITHSCORES')
                for i = 1, #oldMembers, 2 do
                    local member = oldMembers[i]
                    if redis.call('ZSCORE', tmpKey, member) == false then
                        redis.call('ZADD', tmpKey, oldMembers[i + 1], member)
                    end
                end
            end

            local size = redis.call('ZCARD', tmpKey)
            if size > maxSize then
                redis.call('ZREMRANGEBYRANK', tmpKey, 0, size - maxSize - 1)
            end
            redis.call('EXPIRE', tmpKey, ttlSeconds)
            redis.call('RENAME', tmpKey, realKey)
            return 1
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedOutboxProperties feedOutboxProperties;

    /**
     * outbox 重建互斥锁过期秒数（默认 30）。 {@code int}
     */
    @Value("${feed.outbox.rebuildLockSeconds:30}")
    private int rebuildLockSeconds;

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
        if (!tryAcquireRebuildLock(authorId)) {
            return;
        }
        String outboxKey = outboxKey(authorId);
        String tmpKey = outboxTmpKey(authorId, System.nanoTime());
        List<Object> args = new ArrayList<>();
        args.add(outboxKey);
        args.add(tmpKey);
        args.add(String.valueOf(ttl().getSeconds()));
        args.add(String.valueOf(maxSize()));
        args.add(String.valueOf(rebuildWindowSize()));
        int countIndex = args.size();
        args.add("0");
        int count = 0;
        if (entries != null && !entries.isEmpty()) {
            for (FeedInboxEntryVO entry : entries) {
                if (entry == null || entry.getPostId() == null || entry.getPublishTimeMs() == null) {
                    continue;
                }
                args.add(entry.getPostId().toString());
                args.add(entry.getPublishTimeMs().toString());
                count++;
            }
        }
        args.set(countIndex, String.valueOf(count));
        stringRedisTemplate.execute(REPLACE_OUTBOX_SCRIPT, List.of(outboxKey), args.toArray());
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

    private String rebuildLockKey(Long authorId) {
        return KEY_OUTBOX_REBUILD_LOCK_PREFIX + authorId;
    }

    private boolean tryAcquireRebuildLock(Long authorId) {
        int seconds = Math.max(1, rebuildLockSeconds);
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(rebuildLockKey(authorId), "1", Duration.ofSeconds(seconds));
        return Boolean.TRUE.equals(locked);
    }

    private Duration ttl() {
        int ttlDays = Math.max(1, feedOutboxProperties.getTtlDays());
        return Duration.ofDays(ttlDays);
    }

    private int maxSize() {
        return Math.max(1, feedOutboxProperties.getMaxSize());
    }

    private int rebuildWindowSize() {
        return Math.min(Math.max(0, feedOutboxProperties.getRebuildMergeWindowSize()), maxSize());
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
}
