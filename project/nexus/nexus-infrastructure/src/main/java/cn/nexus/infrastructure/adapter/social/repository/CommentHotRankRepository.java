package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 评论热榜仓储 Redis 实现（ZSET）。
 *
 * <p>Key：comment:hot:{postId}</p>
 *
 * @author codex
 * @since 2026-01-14
 */
@Repository
@RequiredArgsConstructor
public class CommentHotRankRepository implements ICommentHotRankRepository {

    private static final String KEY_PREFIX = "comment:hot:";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void upsert(Long postId, Long rootCommentId, double score) {
        if (postId == null || rootCommentId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().add(key(postId), rootCommentId.toString(), score);
    }

    @Override
    public void remove(Long postId, Long rootCommentId) {
        if (postId == null || rootCommentId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(key(postId), rootCommentId.toString());
    }

    @Override
    public List<Long> topIds(Long postId, int limit) {
        if (postId == null) {
            return List.of();
        }
        int normalized = Math.max(1, limit);
        Set<String> set = stringRedisTemplate.opsForZSet().reverseRange(key(postId), 0, normalized - 1);
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(set.size());
        for (String s : set) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(s));
            } catch (Exception ignored) {
                // 跳过坏数据，避免整页失败
            }
        }
        return ids;
    }

    private String key(Long postId) {
        return KEY_PREFIX + postId;
    }
}

