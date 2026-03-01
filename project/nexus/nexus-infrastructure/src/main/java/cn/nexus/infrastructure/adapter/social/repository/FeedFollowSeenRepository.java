package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedFollowSeenRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * FOLLOW 时间线“已读”记录 Redis 实现（SET）。
 *
 * <p>Key 规则在 domain 接口注释中已定死：不要改名。</p>
 */
@Repository
@RequiredArgsConstructor
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

