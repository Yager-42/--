package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedRecommendSessionRepository;
import cn.nexus.infrastructure.config.FeedRecommendProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 推荐流 session cache Redis 实现（LIST/SET/STRING）。
 *
 * <p>Key 规则在 domain 接口注释中已定死：不要改名。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Repository
@RequiredArgsConstructor
public class FeedRecommendSessionRepository implements IFeedRecommendSessionRepository {

    private static final String KEY_SESSION_LIST_PREFIX = "feed:rec:session:";
    private static final String KEY_SESSION_SEEN_PREFIX = "feed:rec:seen:";
    private static final String KEY_LATEST_CURSOR_PREFIX = "feed:rec:latestCursor:";

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedRecommendProperties feedRecommendProperties;

    @Override
    public boolean sessionExists(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Boolean exists = stringRedisTemplate.hasKey(sessionListKey(userId, sessionId));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public long size(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return 0L;
        }
        Long size = stringRedisTemplate.opsForList().size(sessionListKey(userId, sessionId));
        return size == null ? 0L : Math.max(0L, size);
    }

    @Override
    public List<Long> range(Long userId, String sessionId, long startIndex, long endIndex) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        if (startIndex < 0 || endIndex < startIndex) {
            return List.of();
        }
        List<String> raw = stringRedisTemplate.opsForList().range(sessionListKey(userId, sessionId), startIndex, endIndex);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        touch(userId, sessionId);
        List<Long> ids = new ArrayList<>(raw.size());
        for (String s : raw) {
            ids.add(parseLong(s));
        }
        return ids;
    }

    @Override
    public Long get(Long userId, String sessionId, long index) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (index < 0) {
            return null;
        }
        String raw = stringRedisTemplate.opsForList().index(sessionListKey(userId, sessionId), index);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        touch(userId, sessionId);
        return parseLong(raw);
    }

    @Override
    public int appendCandidates(Long userId, String sessionId, List<Long> postIds) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        if (postIds == null || postIds.isEmpty()) {
            return 0;
        }
        String listKey = sessionListKey(userId, sessionId);
        String seenKey = sessionSeenKey(userId, sessionId);

        int appended = 0;
        for (Long id : postIds) {
            if (id == null) {
                continue;
            }
            Long added = stringRedisTemplate.opsForSet().add(seenKey, id.toString());
            if (added == null || added <= 0) {
                continue;
            }
            stringRedisTemplate.opsForList().rightPush(listKey, id.toString());
            appended++;
        }

        touch(userId, sessionId);
        return appended;
    }

    @Override
    public String getLatestCursor(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().get(latestCursorKey(userId, sessionId));
        touch(userId, sessionId);
        return value;
    }

    @Override
    public void setLatestCursor(Long userId, String sessionId, String latestCursor) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (latestCursor == null || latestCursor.isBlank()) {
            return;
        }
        stringRedisTemplate.opsForValue().set(latestCursorKey(userId, sessionId), latestCursor.trim(), ttl());
        touch(userId, sessionId);
    }

    @Override
    public void deleteSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(sessionListKey(userId, sessionId));
        stringRedisTemplate.delete(sessionSeenKey(userId, sessionId));
        stringRedisTemplate.delete(latestCursorKey(userId, sessionId));
    }

    private void touch(Long userId, String sessionId) {
        Duration ttl = ttl();
        String listKey = sessionListKey(userId, sessionId);
        String seenKey = sessionSeenKey(userId, sessionId);
        String cursorKey = latestCursorKey(userId, sessionId);
        stringRedisTemplate.expire(listKey, ttl);
        stringRedisTemplate.expire(seenKey, ttl);
        stringRedisTemplate.expire(cursorKey, ttl);
    }

    private Duration ttl() {
        int ttlMinutes = Math.max(1, feedRecommendProperties.getSessionTtlMinutes());
        return Duration.ofMinutes(ttlMinutes);
    }

    private String sessionListKey(Long userId, String sessionId) {
        return KEY_SESSION_LIST_PREFIX + userId + ":" + sessionId;
    }

    private String sessionSeenKey(Long userId, String sessionId) {
        return KEY_SESSION_SEEN_PREFIX + userId + ":" + sessionId;
    }

    private String latestCursorKey(Long userId, String sessionId) {
        return KEY_LATEST_CURSOR_PREFIX + userId + ":" + sessionId;
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
