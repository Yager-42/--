package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的关注计数缓存，缺失时回源数据库。
 */
@Component
@RequiredArgsConstructor
public class RelationCachePort implements IRelationCachePort {

    private final StringRedisTemplate redisTemplate;
    private final IRelationRepository relationRepository;

    private static final String KEY_FOLLOW_COUNT = "social:follow:count:";
    private static final long TTL_SECONDS = 3600;

    @Override
    public long getFollowCount(Long sourceId) {
        String key = KEY_FOLLOW_COUNT + sourceId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            try {
                return Long.parseLong(cached);
            } catch (NumberFormatException ignored) {
            }
        }
        int count = relationRepository.countRelationsBySource(sourceId, 1);
        redisTemplate.opsForValue().set(key, String.valueOf(count), TTL_SECONDS, TimeUnit.SECONDS);
        return count;
    }

    @Override
    public void incrFollow(Long sourceId) {
        String key = KEY_FOLLOW_COUNT + sourceId;
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void decrFollow(Long sourceId) {
        String key = KEY_FOLLOW_COUNT + sourceId;
        redisTemplate.opsForValue().decrement(key);
        redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
    }
}
