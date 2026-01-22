package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedCoreFansRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Feed 铁粉仓储 Redis 实现（SET）。
 *
 * <p>Key：feed:corefans:{authorId}</p>
 *
 * @author codex
 * @since 2026-01-14
 */
@Repository
@RequiredArgsConstructor
public class FeedCoreFansRepository implements IFeedCoreFansRepository {

    private static final String KEY_CORE_FANS_PREFIX = "feed:corefans:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 铁粉集合过期天数（默认 30）。{@code int}
     */
    @Value("${feed.corefans.ttlDays:30}")
    private int ttlDays;

    @Override
    public void addCoreFan(Long authorId, Long followerId) {
        if (authorId == null || followerId == null) {
            return;
        }
        String key = coreFansKey(authorId);
        stringRedisTemplate.opsForSet().add(key, followerId.toString());
        stringRedisTemplate.expire(key, Duration.ofDays(Math.max(1, ttlDays)));
    }

    @Override
    public boolean isCoreFan(Long authorId, Long followerId) {
        if (authorId == null || followerId == null) {
            return false;
        }
        Boolean member = stringRedisTemplate.opsForSet().isMember(coreFansKey(authorId), followerId.toString());
        return Boolean.TRUE.equals(member);
    }

    @Override
    public List<Long> listCoreFans(Long authorId, int limit) {
        if (authorId == null) {
            return List.of();
        }
        int normalizedLimit = Math.max(0, limit);
        if (normalizedLimit == 0) {
            return List.of();
        }
        Set<String> members = stringRedisTemplate.opsForSet().members(coreFansKey(authorId));
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(Math.min(members.size(), normalizedLimit));
        for (String member : members) {
            if (result.size() >= normalizedLimit) {
                break;
            }
            Long id = parseLong(member);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private String coreFansKey(Long authorId) {
        return KEY_CORE_FANS_PREFIX + authorId;
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
