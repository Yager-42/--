package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedFollowSeenRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * FOLLOW 时间线“已读”记录 Redis 实现（SET）。
 *
 * <p>Key 规则在 domain 接口注释中已定死：不要改名。</p>
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class FeedFollowSeenRepository implements IFeedFollowSeenRepository {

    private static final String KEY_PREFIX = "feed:follow:seen:";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean markSeen(Long userId, Long postId) {
        if (userId == null || postId == null) {
            return false;
        }
        Long added = stringRedisTemplate.opsForSet().add(key(userId), postId.toString());
        return added != null && added > 0;
    }

    @Override
    public boolean isSeen(Long userId, Long postId) {
        if (userId == null || postId == null) {
            return false;
        }
        Boolean member = stringRedisTemplate.opsForSet().isMember(key(userId), postId.toString());
        return Boolean.TRUE.equals(member);
    }

    @Override
    public Set<Long> batchSeen(Long userId, List<Long> postIds) {
        if (userId == null || postIds == null || postIds.isEmpty()) {
            return Set.of();
        }
        List<Long> normalized = new ArrayList<>();
        LinkedHashSet<Long> dedup = new LinkedHashSet<>();
        for (Long postId : postIds) {
            if (postId != null && dedup.add(postId)) {
                normalized.add(postId);
            }
        }
        if (normalized.isEmpty()) {
            return Set.of();
        }
        String redisKey = key(userId);
        List<Object> raw;
        try {
            raw = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) {
                    for (Long postId : normalized) {
                        operations.opsForSet().isMember(redisKey, postId.toString());
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            log.warn("feed follow seen batch query failed, userId={}", userId, e);
            return Set.of();
        }
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<Long> seen = new LinkedHashSet<>();
        int size = Math.min(normalized.size(), raw.size());
        for (int i = 0; i < size; i++) {
            if (Boolean.TRUE.equals(raw.get(i))) {
                seen.add(normalized.get(i));
            }
        }
        return seen;
    }

    @Override
    public void expire(Long userId, int ttlDays) {
        if (userId == null) {
            return;
        }
        int days = Math.max(1, ttlDays);
        stringRedisTemplate.expire(key(userId), Duration.ofDays(days));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}

