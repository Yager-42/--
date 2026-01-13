package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.domain.social.model.valobj.FeedIdPageVO;
import cn.nexus.infrastructure.config.FeedInboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String KEY_TMP_PREFIX = "feed:inbox:tmp:";
    private static final String KEY_REBUILD_LOCK_PREFIX = "feed:inbox:rebuild:lock:";
    private static final String INBOX_NO_MORE_MEMBER = "__NOMORE__";

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedInboxProperties feedInboxProperties;

    /**
     * inbox 重建互斥锁过期秒数（默认 30）。 {@code int}
     */
    @Value("${feed.rebuild.lockSeconds:30}")
    private int rebuildLockSeconds;

    @Override
    public void addToInbox(Long userId, Long postId, Long publishTimeMs) {
        if (userId == null || postId == null || publishTimeMs == null) {
            return;
        }
        String key = inboxKey(userId);
        stringRedisTemplate.opsForZSet().add(key, postId.toString(), publishTimeMs.doubleValue());
        expireIfNeeded(key);
        trimToMaxSize(key);
    }

    @Override
    public boolean inboxExists(Long userId) {
        if (userId == null) {
            return false;
        }
        Boolean exists = stringRedisTemplate.hasKey(inboxKey(userId));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void replaceInbox(Long userId, List<FeedInboxEntryVO> entries) {
        if (userId == null) {
            return;
        }
        if (!tryAcquireRebuildLock(userId)) {
            return;
        }

        String tmpKey = inboxTmpKey(userId, System.currentTimeMillis());
        if (entries != null && !entries.isEmpty()) {
            for (FeedInboxEntryVO entry : entries) {
                if (entry == null || entry.getPostId() == null || entry.getPublishTimeMs() == null) {
                    continue;
                }
                stringRedisTemplate.opsForZSet()
                        .add(tmpKey, entry.getPostId().toString(), entry.getPublishTimeMs().doubleValue());
            }
        }

        stringRedisTemplate.opsForZSet().add(tmpKey, INBOX_NO_MORE_MEMBER, 0D);
        stringRedisTemplate.expire(tmpKey, ttl());
        trimToMaxSize(tmpKey);

        String inboxKey = inboxKey(userId);
        stringRedisTemplate.rename(tmpKey, inboxKey);
        stringRedisTemplate.expire(inboxKey, ttl());
    }

    @Override
    public FeedIdPageVO pageInbox(Long userId, String cursor, int limit) {
        if (userId == null) {
            return FeedIdPageVO.builder().postIds(List.of()).nextCursor(null).build();
        }
        int normalizedLimit = Math.max(1, limit);
        String key = inboxKey(userId);
        stringRedisTemplate.expire(key, ttl());

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

        List<String> filteredIds = filterNoMore(ids);
        List<Long> postIds = toLongList(filteredIds);
        String nextCursor = filteredIds.isEmpty() ? null : filteredIds.get(filteredIds.size() - 1);
        return FeedIdPageVO.builder()
                .postIds(postIds)
                .nextCursor(nextCursor)
                .build();
    }

    private String inboxKey(Long userId) {
        return KEY_INBOX_PREFIX + userId;
    }

    private String rebuildLockKey(Long userId) {
        return KEY_REBUILD_LOCK_PREFIX + userId;
    }

    private String inboxTmpKey(Long userId, long epochMs) {
        return KEY_TMP_PREFIX + userId + ":" + epochMs;
    }

    private boolean tryAcquireRebuildLock(Long userId) {
        int seconds = Math.max(1, rebuildLockSeconds);
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(rebuildLockKey(userId), "1", Duration.ofSeconds(seconds));
        return Boolean.TRUE.equals(locked);
    }

    private Duration ttl() {
        int ttlDays = Math.max(1, feedInboxProperties.getTtlDays());
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
        int maxSize = Math.max(1, feedInboxProperties.getMaxSize());
        Long size = stringRedisTemplate.opsForZSet().zCard(key);
        if (size == null || size <= maxSize) {
            return;
        }
        long removeCount = size - maxSize;
        stringRedisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
    }

    private List<String> filterNoMore(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> filtered = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (INBOX_NO_MORE_MEMBER.equals(id)) {
                continue;
            }
            filtered.add(id);
        }
        return filtered;
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
