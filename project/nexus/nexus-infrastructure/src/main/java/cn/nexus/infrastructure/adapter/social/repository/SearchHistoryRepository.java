package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.ISearchHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * 搜索历史仓储 Redis 实现（LIST）。
 *
 * <p>Key：search:history:{userId}</p>
 */
@Repository
@RequiredArgsConstructor
public class SearchHistoryRepository implements ISearchHistoryRepository {

    private static final String KEY_PREFIX = "search:history:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${search.history.maxSize:20}")
    private int maxSize;

    @Value("${search.history.ttlDays:90}")
    private int ttlDays;

    @Override
    public void record(Long userId, String keyword) {
        if (userId == null || keyword == null || keyword.isBlank()) {
            return;
        }
        String k = keyword.trim();
        if (k.isEmpty()) {
            return;
        }
        String key = KEY_PREFIX + userId;
        int size = Math.max(1, maxSize);

        // 去重 -> 头插 -> 截断；写入失败由上层 best-effort 处理。
        stringRedisTemplate.opsForList().remove(key, 0, k);
        stringRedisTemplate.opsForList().leftPush(key, k);
        stringRedisTemplate.opsForList().trim(key, 0, size - 1);
        stringRedisTemplate.expire(key, ttl());
    }

    @Override
    public void clear(Long userId) {
        if (userId == null) {
            return;
        }
        stringRedisTemplate.delete(KEY_PREFIX + userId);
    }

    private Duration ttl() {
        int days = Math.max(1, ttlDays);
        return Duration.ofDays(days);
    }
}

