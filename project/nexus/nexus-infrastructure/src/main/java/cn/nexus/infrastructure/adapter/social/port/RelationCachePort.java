package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis Hash 的关系计数缓存实现。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
@Component
@RequiredArgsConstructor
public class RelationCachePort implements IRelationCachePort {

    private static final String KEY_PREFIX = "social:relation:count:";
    private static final String FIELD_FOLLOWING = "followingCount";
    private static final String FIELD_FOLLOWER = "followerCount";
    private static final long BASE_TTL_SECONDS = 1800;
    private static final long JITTER_SECONDS = 300;

    private final StringRedisTemplate redisTemplate;
    private final IRelationRepository relationRepository;

    /**
     * 执行 getFollowingCount 逻辑。
     *
     * @param sourceId sourceId 参数。类型：{@link Long}
     * @return 处理结果。类型：{@code long}
     */
    @Override
    public long getFollowingCount(Long sourceId) {
        if (sourceId == null) {
            return 0L;
        }
        String key = key(sourceId);
        Long cached = parseLong(redisTemplate.opsForHash().get(key, FIELD_FOLLOWING));
        if (cached != null && cached >= 0) {
            return cached;
        }
        long count = relationRepository.countActiveRelationsBySource(sourceId, 1);
        redisTemplate.opsForHash().put(key, FIELD_FOLLOWING, String.valueOf(count));
        expire(key);
        return count;
    }

    /**
     * 执行 getFollowerCount 逻辑。
     *
     * @param targetId 目标 ID。类型：{@link Long}
     * @return 处理结果。类型：{@code long}
     */
    @Override
    public long getFollowerCount(Long targetId) {
        if (targetId == null) {
            return 0L;
        }
        String key = key(targetId);
        Long cached = parseLong(redisTemplate.opsForHash().get(key, FIELD_FOLLOWER));
        if (cached != null && cached >= 0) {
            return cached;
        }
        long count = relationRepository.countFollowerIds(targetId);
        redisTemplate.opsForHash().put(key, FIELD_FOLLOWER, String.valueOf(count));
        expire(key);
        return count;
    }

    /**
     * 执行 incrFollowing 逻辑。
     *
     * @param sourceId sourceId 参数。类型：{@link Long}
     * @param delta delta 参数。类型：{@code long}
     */
    @Override
    public void incrFollowing(Long sourceId, long delta) {
        adjust(sourceId, FIELD_FOLLOWING, delta);
    }

    /**
     * 执行 incrFollower 逻辑。
     *
     * @param targetId 目标 ID。类型：{@link Long}
     * @param delta delta 参数。类型：{@code long}
     */
    @Override
    public void incrFollower(Long targetId, long delta) {
        adjust(targetId, FIELD_FOLLOWER, delta);
    }

    /**
     * 执行 evict 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     */
    @Override
    public void evict(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(key(userId));
    }

    private void adjust(Long userId, String field, long delta) {
        if (userId == null || delta == 0) {
            return;
        }
        String key = key(userId);
        Long updated = redisTemplate.opsForHash().increment(key, field, delta);
        if (updated != null && updated < 0) {
            redisTemplate.opsForHash().put(key, field, "0");
        }
        expire(key);
    }

    private void expire(String key) {
        long ttl = BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(JITTER_SECONDS + 1);
        redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
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
