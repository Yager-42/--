package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedNegativeFeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;

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
    private static final String KEY_NEG_TYPE_PREFIX = "feed:neg:type:";

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

    @Override
    public void addContentType(Long userId, Integer mediaType) {
        if (userId == null || mediaType == null) {
            return;
        }
        stringRedisTemplate.opsForSet().add(negTypeKey(userId), mediaType.toString());
    }

    @Override
    public void removeContentType(Long userId, Integer mediaType) {
        if (userId == null || mediaType == null) {
            return;
        }
        stringRedisTemplate.opsForSet().remove(negTypeKey(userId), mediaType.toString());
    }

    @Override
    public Set<Integer> listContentTypes(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        Set<String> members = stringRedisTemplate.opsForSet().members(negTypeKey(userId));
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<Integer> result = new HashSet<>();
        for (String member : members) {
            if (member == null || member.isBlank()) {
                continue;
            }
            try {
                result.add(Integer.parseInt(member));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private String negKey(Long userId) {
        return KEY_NEG_PREFIX + userId;
    }

    private String negTypeKey(Long userId) {
        return KEY_NEG_TYPE_PREFIX + userId;
    }
}
