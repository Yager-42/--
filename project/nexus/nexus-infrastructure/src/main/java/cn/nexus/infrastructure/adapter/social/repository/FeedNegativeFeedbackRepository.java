package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedNegativeFeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Feed 负反馈仓储 Redis 实现（SET/HASH）。
 *
 * <p>Key：</p>
 * <ul>
 *   <li>{@code feed:neg:{userId}}：用户负反馈 postId 集合（SET）</li>
 *   <li>{@code feed:neg:postType:{userId}}：用户负反馈 postType 集合（SET）</li>
 *   <li>{@code feed:neg:postTypeByPost:{userId}}：用户对某个 post 点选的类型（HASH：postId->postType，用于撤销反查）</li>
 * </ul>
 *
 * @author codex
 * @since 2026-01-12
 */
@Repository
@RequiredArgsConstructor
public class FeedNegativeFeedbackRepository implements IFeedNegativeFeedbackRepository {

    private static final String KEY_NEG_PREFIX = "feed:neg:";
    private static final String KEY_NEG_POST_TYPE_PREFIX = "feed:neg:postType:";
    private static final String KEY_NEG_POST_TYPE_BY_POST_PREFIX = "feed:neg:postTypeByPost:";

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
    public void addPostType(Long userId, String postType) {
        if (userId == null || postType == null || postType.isBlank()) {
            return;
        }
        stringRedisTemplate.opsForSet().add(negPostTypeKey(userId), postType.trim());
    }

    @Override
    public void removePostType(Long userId, String postType) {
        if (userId == null || postType == null || postType.isBlank()) {
            return;
        }
        stringRedisTemplate.opsForSet().remove(negPostTypeKey(userId), postType.trim());
    }

    @Override
    public Set<String> listPostTypes(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        Set<String> members = stringRedisTemplate.opsForSet().members(negPostTypeKey(userId));
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String member : members) {
            if (member == null || member.isBlank()) {
                continue;
            }
            result.add(member.trim());
        }
        return result;
    }

    @Override
    public void saveSelectedPostType(Long userId, Long postId, String postType) {
        if (userId == null || postId == null || postType == null || postType.isBlank()) {
            return;
        }
        String normalized = postType.trim();
        if (normalized.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForHash().put(negPostTypeByPostKey(userId), postId.toString(), normalized);
        stringRedisTemplate.opsForSet().add(negPostTypeKey(userId), normalized);
    }

    @Override
    public String getSelectedPostType(Long userId, Long postId) {
        if (userId == null || postId == null) {
            return null;
        }
        Object value = stringRedisTemplate.opsForHash().get(negPostTypeByPostKey(userId), postId.toString());
        if (value == null) {
            return null;
        }
        String type = value.toString();
        return type == null ? null : type.trim();
    }

    @Override
    public void removeSelectedPostType(Long userId, Long postId) {
        if (userId == null || postId == null) {
            return;
        }
        String key = negPostTypeByPostKey(userId);
        String field = postId.toString();
        Object value = stringRedisTemplate.opsForHash().get(key, field);
        if (value == null) {
            return;
        }
        String removed = value.toString();
        if (removed == null || removed.isBlank()) {
            stringRedisTemplate.opsForHash().delete(key, field);
            return;
        }
        String normalized = removed.trim();
        stringRedisTemplate.opsForHash().delete(key, field);

        // 若其它 post 仍点选了同类型，则不移除类型过滤
        Collection<Object> values = stringRedisTemplate.opsForHash().values(key);
        if (values != null) {
            for (Object v : values) {
                if (v == null) {
                    continue;
                }
                String t = v.toString();
                if (t != null && normalized.equals(t.trim())) {
                    return;
                }
            }
        }
        stringRedisTemplate.opsForSet().remove(negPostTypeKey(userId), normalized);
    }

    private String negKey(Long userId) {
        return KEY_NEG_PREFIX + userId;
    }

    private String negPostTypeKey(Long userId) {
        return KEY_NEG_POST_TYPE_PREFIX + userId;
    }

    private String negPostTypeByPostKey(Long userId) {
        return KEY_NEG_POST_TYPE_BY_POST_PREFIX + userId;
    }
}
