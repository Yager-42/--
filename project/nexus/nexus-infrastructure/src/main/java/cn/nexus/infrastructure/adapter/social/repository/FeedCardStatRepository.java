package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedCardStatRepository;
import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FeedCardStatRepository implements IFeedCardStatRepository {

    private static final String KEY_PREFIX = "feed:card:stat:";
    private static final String FIELD_LIKE_COUNT = "likeCount";
    private static final long BASE_TTL_SECONDS = 600;
    private static final long JITTER_SECONDS = 180;

    private final StringRedisTemplate stringRedisTemplate;

    private final Cache<Long, FeedCardStatVO> l1 = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofSeconds(2))
            .build();

    @Override
    public Map<Long, FeedCardStatVO> getBatch(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FeedCardStatVO> result = new HashMap<>();
        for (Long postId : postIds) {
            if (postId == null) {
                continue;
            }
            FeedCardStatVO cached = l1.getIfPresent(postId);
            if (cached != null) {
                result.put(postId, cached);
                continue;
            }
            Object raw = stringRedisTemplate.opsForHash().get(key(postId), FIELD_LIKE_COUNT);
            if (raw == null) {
                continue;
            }
            Long count = parseLong(raw);
            if (count == null) {
                continue;
            }
            FeedCardStatVO vo = FeedCardStatVO.builder().postId(postId).likeCount(count).build();
            result.put(postId, vo);
            l1.put(postId, vo);
        }
        return result;
    }

    @Override
    public void saveBatch(List<FeedCardStatVO> stats) {
        if (stats == null || stats.isEmpty()) {
            return;
        }
        for (FeedCardStatVO stat : stats) {
            if (stat == null || stat.getPostId() == null) {
                continue;
            }
            stringRedisTemplate.opsForHash().put(key(stat.getPostId()), FIELD_LIKE_COUNT, String.valueOf(Math.max(0L, stat.getLikeCount() == null ? 0L : stat.getLikeCount())));
            stringRedisTemplate.expire(key(stat.getPostId()), ttlSeconds(), TimeUnit.SECONDS);
            l1.put(stat.getPostId(), stat);
        }
    }

    public void evictLocal(Long postId) {
        if (postId == null) {
            return;
        }
        l1.invalidate(postId);
    }

    public void evictRedis(Long postId) {
        if (postId == null) {
            return;
        }
        stringRedisTemplate.delete(key(postId));
    }

    private String key(Long postId) {
        return KEY_PREFIX + postId;
    }

    private Long parseLong(Object raw) {
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (Exception ignored) {
            return null;
        }
    }

    private long ttlSeconds() {
        return BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(JITTER_SECONDS + 1);
    }
}
