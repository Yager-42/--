package cn.nexus.infrastructure.adapter.counter.port;

import cn.nexus.domain.counter.adapter.port.IUserCounterPort;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 用户维度统一计数 Redis 实现。
 *
 * @author codex
 * @since 2026-04-02
 */
@Component
@RequiredArgsConstructor
public class UserCounterPort implements IUserCounterPort {

    private static final String KEY_PREFIX = "counter:user:";
    private static final long BASE_TTL_SECONDS = 1800;
    private static final long JITTER_SECONDS = 300;

    private final StringRedisTemplate redisTemplate;
    private final IRelationRepository relationRepository;

    @Override
    public long getCount(Long userId, UserCounterType counterType) {
        if (userId == null || counterType == null) {
            return 0L;
        }
        String key = key(userId, counterType);
        Long cached = parseLong(redisTemplate.opsForValue().get(key));
        if (cached != null && cached >= 0) {
            return cached;
        }
        long rebuilt = rebuild(userId, counterType);
        redisTemplate.opsForValue().set(key, String.valueOf(rebuilt));
        expire(key);
        return rebuilt;
    }

    @Override
    public long increment(Long userId, UserCounterType counterType, long delta) {
        if (userId == null || counterType == null) {
            return 0L;
        }
        if (delta == 0) {
            return getCount(userId, counterType);
        }
        String key = key(userId, counterType);
        Long updated = redisTemplate.opsForValue().increment(key, delta);
        long safe = updated == null ? 0L : Math.max(0L, updated);
        if (safe != (updated == null ? 0L : updated)) {
            redisTemplate.opsForValue().set(key, "0");
        }
        expire(key);
        return safe;
    }

    @Override
    public void setCount(Long userId, UserCounterType counterType, long count) {
        if (userId == null || counterType == null) {
            return;
        }
        String key = key(userId, counterType);
        redisTemplate.opsForValue().set(key, String.valueOf(Math.max(0L, count)));
        expire(key);
    }

    @Override
    public void evict(Long userId, UserCounterType counterType) {
        if (userId == null || counterType == null) {
            return;
        }
        redisTemplate.delete(key(userId, counterType));
    }

    private long rebuild(Long userId, UserCounterType counterType) {
        return switch (counterType) {
            case FOLLOWING -> relationRepository.countActiveRelationsBySource(userId, 1);
            case FOLLOWER -> relationRepository.countFollowerIds(userId);
            case POST, LIKE_RECEIVED, FAVORITE_RECEIVED -> 0L;
        };
    }

    private String key(Long userId, UserCounterType counterType) {
        return KEY_PREFIX + counterType.getCode() + ":" + userId;
    }

    private void expire(String key) {
        long ttl = BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(JITTER_SECONDS + 1);
        redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
    }

    private Long parseLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
