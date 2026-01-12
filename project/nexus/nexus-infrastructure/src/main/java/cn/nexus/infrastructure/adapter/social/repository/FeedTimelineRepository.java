package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.valobj.FeedIdPageVO;
import cn.nexus.infrastructure.config.FeedInboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Feed InboxTimeline 仓储 Redis 实现（ZSET）。
 *
 * <p>Key：feed:inbox:{userId}</p>
 *
 * @author codex
 * @since 2026-01-12
 */
@Repository
@RequiredArgsConstructor
public class FeedTimelineRepository implements IFeedTimelineRepository {

    private static final String KEY_INBOX_PREFIX = "feed:inbox:";

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedInboxProperties feedInboxProperties;

    @Override
    public void addToInbox(Long userId, Long postId, Long publishTimeMs) {
        if (userId == null || postId == null || publishTimeMs == null) {
            return;
        }
        String key = inboxKey(userId);
        stringRedisTemplate.opsForZSet().add(key, postId.toString(), publishTimeMs.doubleValue());
        stringRedisTemplate.expire(key, ttl());
        trimToMaxSize(key);
    }

    @Override
    public FeedIdPageVO pageInbox(Long userId, String cursor, int limit) {
        if (userId == null) {
            return FeedIdPageVO.builder().postIds(List.of()).nextCursor(null).build();
        }
        int normalizedLimit = Math.max(1, limit);
        String key = inboxKey(userId);

        List<String> ids;
        if (cursor == null || cursor.isBlank()) {
            ids = toList(stringRedisTemplate.opsForZSet().reverseRange(key, 0, normalizedLimit - 1));
        } else {
            Long rank = stringRedisTemplate.opsForZSet().reverseRank(key, cursor);
            if (rank == null) {
                ids = List.of();
            } else {
                ids = toList(stringRedisTemplate.opsForZSet().reverseRange(key, rank + 1, rank + normalizedLimit));
            }
        }

        List<Long> postIds = toLongList(ids);
        String nextCursor = ids.isEmpty() ? null : ids.get(ids.size() - 1);
        return FeedIdPageVO.builder()
                .postIds(postIds)
                .nextCursor(nextCursor)
                .build();
    }

    private String inboxKey(Long userId) {
        return KEY_INBOX_PREFIX + userId;
    }

    private Duration ttl() {
        int ttlDays = Math.max(1, feedInboxProperties.getTtlDays());
        return Duration.ofDays(ttlDays);
    }

    private void trimToMaxSize(String key) {
        int maxSize = Math.max(1, feedInboxProperties.getMaxSize());
        Long size = stringRedisTemplate.opsForZSet().zCard(key);
        if (size == null || size <= maxSize) {
            return;
        }
        long removeCount = size - maxSize;
        stringRedisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
    }

    private List<String> toList(Set<String> set) {
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(set);
    }

    private List<Long> toLongList(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> list = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            try {
                list.add(Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                // 跳过异常数据，避免一次坏数据导致整页失败
            }
        }
        return list;
    }
}
