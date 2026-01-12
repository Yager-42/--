package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedNegativeFeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Feed 负反馈仓储 Redis 实现（SET）。
 *
 * <p>Key：feed:neg:{userId}</p>
 *
 * @author codex
 * @since 2026-01-12
 */
@Repository
@RequiredArgsConstructor
public class FeedNegativeFeedbackRepository implements IFeedNegativeFeedbackRepository {

    private static final String KEY_NEG_PREFIX = "feed:neg:";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void add(Long userId, Long targetId, String type, String reasonCode) {
        if (userId == null || targetId == null) {
            return;
        }
        stringRedisTemplate.opsForSet().add(negKey(userId), targetId.toString());
    }

    @Override
    public void remove(Long userId, Long targetId) {
        if (userId == null || targetId == null) {
            return;
        }
        stringRedisTemplate.opsForSet().remove(negKey(userId), targetId.toString());
    }

    @Override
    public boolean contains(Long userId, Long targetId) {
        if (userId == null || targetId == null) {
            return false;
        }
        Boolean member = stringRedisTemplate.opsForSet().isMember(negKey(userId), targetId.toString());
        return Boolean.TRUE.equals(member);
    }

    private String negKey(Long userId) {
        return KEY_NEG_PREFIX + userId;
    }
}

