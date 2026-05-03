package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedGlobalLatestRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.config.FeedGlobalLatestProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 全站 latest 仓储 Redis 实现（ZSET）。
 *
 * <p>Key：{@code feed:global:latest}，score=publishTimeMs，member=postId</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Repository
@RequiredArgsConstructor
public class FeedGlobalLatestRepository implements IFeedGlobalLatestRepository {

    private static final String KEY_GLOBAL_LATEST = "feed:global:latest";

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedGlobalLatestProperties feedGlobalLatestProperties;

    @Override
    public void addToLatest(Long postId, Long publishTimeMs) {
        if (postId == null || publishTimeMs == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().add(KEY_GLOBAL_LATEST, postId.toString(), publishTimeMs.doubleValue());
        trimToMaxSize();
    }

    @Override
    public void removeFromLatest(Long postId) {
        if (postId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(KEY_GLOBAL_LATEST, postId.toString());
    }

    @Override
    public List<FeedInboxEntryVO> pageLatest(Long cursorTimeMs, Long cursorPostId, int limit) {
        int normalizedLimit = Math.max(1, limit);

        long safeCursorTime = cursorTimeMs == null ? Long.MAX_VALUE : cursorTimeMs;
        long safeCursorPostId = cursorPostId == null ? Long.MAX_VALUE : cursorPostId;
        int fetchCount = normalizedLimit + 20;

        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(KEY_GLOBAL_LATEST, 0D, safeCursorTime, 0, fetchCount);
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
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void trimToMaxSize() {
        int maxSize = Math.max(1, feedGlobalLatestProperties.getMaxSize());
        Long size = stringRedisTemplate.opsForZSet().zCard(KEY_GLOBAL_LATEST);
        if (size == null || size <= maxSize) {
            return;
        }
        long removeCount = size - maxSize;
        stringRedisTemplate.opsForZSet().removeRange(KEY_GLOBAL_LATEST, 0, removeCount - 1);
    }
}
