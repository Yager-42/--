package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedCardRepository;
import cn.nexus.domain.social.model.valobj.FeedCardBaseVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class FeedCardRepository implements IFeedCardRepository {

    private static final String KEY_PREFIX = "feed:card:";
    private static final long BASE_TTL_SECONDS = 1800;
    private static final long JITTER_SECONDS = 600;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private final Cache<Long, FeedCardBaseVO> l1 = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofSeconds(2))
            .build();

    @Override
    public Map<Long, FeedCardBaseVO> getBatch(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FeedCardBaseVO> result = new HashMap<>();
        for (Long postId : postIds) {
            if (postId == null) {
                continue;
            }
            FeedCardBaseVO cached = l1.getIfPresent(postId);
            if (cached != null) {
                result.put(postId, cached);
                continue;
            }
            try {
                String raw = stringRedisTemplate.opsForValue().get(key(postId));
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                FeedCardBaseVO vo = objectMapper.readValue(raw, FeedCardBaseVO.class);
                if (vo != null) {
                    result.put(postId, vo);
                    l1.put(postId, vo);
                }
            } catch (Exception e) {
                log.warn("feed card read failed, postId={}", postId, e);
            }
        }
        return result;
    }

    @Override
    public void saveBatch(List<FeedCardBaseVO> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        for (FeedCardBaseVO card : cards) {
            if (card == null || card.getPostId() == null) {
                continue;
            }
            try {
                stringRedisTemplate.opsForValue().set(key(card.getPostId()), objectMapper.writeValueAsString(card));
                stringRedisTemplate.expire(key(card.getPostId()), ttlSeconds(), TimeUnit.SECONDS);
                l1.put(card.getPostId(), card);
            } catch (Exception e) {
                log.warn("feed card save failed, postId={}", card.getPostId(), e);
            }
        }
    }

    private String key(Long postId) {
        return KEY_PREFIX + postId;
    }

    private long ttlSeconds() {
        return BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(JITTER_SECONDS + 1);
    }
}
