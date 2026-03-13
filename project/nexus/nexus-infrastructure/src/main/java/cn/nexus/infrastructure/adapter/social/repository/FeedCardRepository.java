package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedCardRepository;
import cn.nexus.domain.social.model.valobj.FeedCardBaseVO;
import cn.nexus.infrastructure.config.SocialCacheHotTtlProperties;
import cn.nexus.infrastructure.support.SingleFlight;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jd.platform.hotkey.client.callback.JdHotKeyStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class FeedCardRepository implements IFeedCardRepository {

    private static final String KEY_PREFIX = "feed:card:";
    private static final String HOTKEY_PREFIX = "feed_card__";
    private static final long BASE_TTL_SECONDS = 1800;
    private static final long JITTER_SECONDS = 600;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final SocialCacheHotTtlProperties socialCacheHotTtlProperties;

    private final Cache<Long, FeedCardBaseVO> l1 = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofSeconds(2))
            .build();
    private final SingleFlight singleFlight = new SingleFlight();

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
                FeedCardBaseVO stable = copyStable(cached);
                result.put(postId, stable);
                tryExtendHotCacheTtl(postId, stable);
                continue;
            }
            try {
                String raw = stringRedisTemplate.opsForValue().get(key(postId));
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                FeedCardBaseVO vo = copyStable(objectMapper.readValue(raw, FeedCardBaseVO.class));
                if (vo != null) {
                    FeedCardBaseVO stable = copyStable(vo);
                    result.put(postId, stable);
                    l1.put(postId, copyStable(stable));
                    tryExtendHotCacheTtl(postId, stable);
                }
            } catch (Exception e) {
                log.warn("feed card read failed, postId={}", postId, e);
            }
        }
        return result;
    }

    @Override
    public Map<Long, FeedCardBaseVO> getOrLoadBatch(List<Long> postIds,
                                                    Function<List<Long>, Map<Long, FeedCardBaseVO>> loader) {
        Map<Long, FeedCardBaseVO> result = new HashMap<>(getBatch(postIds));
        List<Long> missIds = collectMissIds(postIds, result.keySet());
        if (missIds.isEmpty()) {
            return result;
        }
        Map<Long, FeedCardBaseVO> rebuilt = singleFlight.execute(
                normalizeInflightKey(missIds),
                () -> loadAndCacheMisses(missIds, loader)
        );
        if (rebuilt != null && !rebuilt.isEmpty()) {
            result.putAll(rebuilt);
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
            FeedCardBaseVO stableCard = copyStable(card);
            try {
                stringRedisTemplate.opsForValue().set(
                        key(stableCard.getPostId()),
                        objectMapper.writeValueAsString(stableCard),
                        ttlSeconds(),
                        TimeUnit.SECONDS
                );
                l1.put(stableCard.getPostId(), copyStable(stableCard));
                tryExtendHotCacheTtl(stableCard.getPostId(), stableCard);
            } catch (Exception e) {
                log.warn("feed card save failed, postId={}", card.getPostId(), e);
            }
        }
    }

    private Map<Long, FeedCardBaseVO> loadAndCacheMisses(List<Long> missIds,
                                                         Function<List<Long>, Map<Long, FeedCardBaseVO>> loader) {
        Map<Long, FeedCardBaseVO> result = new HashMap<>(getBatch(missIds));
        List<Long> unresolved = collectMissIds(missIds, result.keySet());
        if (unresolved.isEmpty() || loader == null) {
            return result;
        }
        Map<Long, FeedCardBaseVO> loaded = loader.apply(unresolved);
        if (loaded == null || loaded.isEmpty()) {
            return result;
        }
        saveBatch(List.copyOf(loaded.values()));
        result.putAll(loaded);
        return result;
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
        try {
            stringRedisTemplate.delete(key(postId));
        } catch (Exception e) {
            log.warn("feed card redis evict failed, postId={}", postId, e);
        }
    }

    private String key(Long postId) {
        return KEY_PREFIX + postId;
    }

    private FeedCardBaseVO copyStable(FeedCardBaseVO card) {
        if (card == null) {
            return null;
        }
        return FeedCardBaseVO.builder()
                .postId(card.getPostId())
                .authorId(card.getAuthorId())
                .text(card.getText())
                .summary(card.getSummary())
                .mediaType(card.getMediaType())
                .mediaInfo(card.getMediaInfo())
                .publishTime(card.getPublishTime())
                .build();
    }

    private void tryExtendHotCacheTtl(Long postId, FeedCardBaseVO card) {
        if (postId == null || card == null || card.getPostId() == null) {
            return;
        }
        long targetTtlSeconds = socialCacheHotTtlProperties.getFeedCardSeconds();
        if (targetTtlSeconds <= 0 || !isHotKeySafe(hotkeyKey(postId))) {
            return;
        }
        String redisKey = key(postId);
        try {
            Long ttl = stringRedisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
            if (ttl == null || ttl <= 0 || ttl >= targetTtlSeconds) {
                return;
            }
            stringRedisTemplate.expire(redisKey, targetTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private String hotkeyKey(Long postId) {
        return HOTKEY_PREFIX + postId;
    }

    private boolean isHotKeySafe(String hotkey) {
        try {
            return JdHotKeyStore.isHotKey(hotkey);
        } catch (Exception e) {
            log.warn("jd-hotkey isHotKey failed, hotkey={}", hotkey, e);
            return false;
        }
    }

    private long ttlSeconds() {
        return BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(JITTER_SECONDS + 1);
    }

    private List<Long> collectMissIds(List<Long> ids, java.util.Set<Long> resolvedIds) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> missIds = new java.util.ArrayList<>();
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || !seen.add(id) || (resolvedIds != null && resolvedIds.contains(id))) {
                continue;
            }
            missIds.add(id);
        }
        return missIds;
    }

    private String normalizeInflightKey(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                normalized.add(id);
            }
        }
        return normalized.stream().sorted().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
    }
}
