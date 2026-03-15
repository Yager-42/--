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
 * @author rr
 * @author codex
 * @since 2026-01-14
 */
@Repository
@RequiredArgsConstructor
public class CommentHotRankRepository implements ICommentHotRankRepository {

    private static final String KEY_PREFIX = "comment:hot:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 写入数据。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     * @param rootCommentId rootCommentId 参数。类型：{@link Long}
     * @param score score 参数。类型：{@code double}
     */
    @Override
    public void upsert(Long postId, Long rootCommentId, double score) {
        if (postId == null || rootCommentId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().add(key(postId), rootCommentId.toString(), score);
    }

    /**
     * 移除数据。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     * @param rootCommentId rootCommentId 参数。类型：{@link Long}
     */
    @Override
    public void remove(Long postId, Long rootCommentId) {
        if (postId == null || rootCommentId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(key(postId), rootCommentId.toString());
    }

    /**
     * 查询前列 ID。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
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

    /**
     * 清空数据。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     */
    @Override
    public void clear(Long postId) {
        if (postId == null) {
            return;
        }
        stringRedisTemplate.delete(key(postId));
    }

    /**
     * 截断到前列。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     * @param keepTop keepTop 参数。类型：{@code int}
     */
    @Override
    public void trimToTop(Long postId, int keepTop) {
        if (postId == null || keepTop <= 0) {
            return;
        }
        Long size = stringRedisTemplate.opsForZSet().zCard(key(postId));
        if (size == null || size <= keepTop) {
            return;
        }
        long stop = size - keepTop - 1;
        if (stop < 0) {
            return;
        }
        // ZREMRANGEBYRANK：按 score 从低到高的 rank 删除，保留最高的 topK。
        stringRedisTemplate.opsForZSet().removeRange(key(postId), 0, stop);
    }

    private String key(Long postId) {
        return KEY_PREFIX + postId;
    }
}
