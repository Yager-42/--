package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IFeedCounterSideEffectPort;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardStatRepository;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Local feed cache side effects driven by counter events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedCounterSideEffectPort implements IFeedCounterSideEffectPort {

    private static final String PUBLIC_INDEX_KEY_PREFIX = "feed:public:index:";
    private static final String FEED_CARD_KEY_PREFIX = "feed:card:";
    private static final String FEED_CARD_STAT_KEY_PREFIX = "feed:card:stat:";

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedCardRepository feedCardRepository;
    private final FeedCardStatRepository feedCardStatRepository;

    @Override
    public void applyPostLikeDelta(Long postId, long delta) {
        if (postId == null || delta == 0) {
            return;
        }
        Set<String> indexKeys = findIndexKeys(postId);
        for (String indexKey : indexKeys) {
            try {
                Long ttlSeconds = stringRedisTemplate.getExpire(indexKey, TimeUnit.SECONDS);
                Set<String> pageKeys = stringRedisTemplate.opsForSet().members(indexKey);
                if (pageKeys == null || pageKeys.isEmpty()) {
                    continue;
                }
                for (String pageKey : pageKeys) {
                    if (pageKey == null || pageKey.isBlank()) {
                        continue;
                    }
                    if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(pageKey))) {
                        stringRedisTemplate.opsForSet().remove(indexKey, pageKey);
                    }
                }
                if (ttlSeconds != null && ttlSeconds > 0) {
                    stringRedisTemplate.expire(indexKey, ttlSeconds, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.warn("apply feed reverse-index side effects failed, postId={}, indexKey={}", postId, indexKey, e);
            }
        }
        // Reuse existing card cache eviction path to guarantee refreshed card/stat read on next request.
        feedCardRepository.evictLocal(postId);
        feedCardRepository.evictRedis(postId);
        feedCardStatRepository.evictLocal(postId);
        feedCardStatRepository.evictRedis(postId);
        deleteQuietly(FEED_CARD_KEY_PREFIX + postId);
        deleteQuietly(FEED_CARD_STAT_KEY_PREFIX + postId);
    }

    private Set<String> findIndexKeys(Long postId) {
        String pattern = PUBLIC_INDEX_KEY_PREFIX + postId + ":*";
        Set<String> keys = new HashSet<>();
        Set<String> scanned = scanKeys(pattern);
        if (scanned != null && !scanned.isEmpty()) {
            keys.addAll(scanned);
        }
        String currentHourKey = PUBLIC_INDEX_KEY_PREFIX + postId + ":" + (System.currentTimeMillis() / 3_600_000L);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(currentHourKey))) {
            keys.add(currentHourKey);
        }
        return keys;
    }

    private Set<String> scanKeys(String pattern) {
        return stringRedisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> result = new HashSet<>();
            if (connection == null) {
                return result;
            }
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(256).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    byte[] keyBytes = cursor.next();
                    if (keyBytes == null || keyBytes.length == 0) {
                        continue;
                    }
                    result.add(new String(keyBytes, StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                throw new IllegalStateException("scan feed reverse-index keys failed, pattern=" + pattern, e);
            }
            return result;
        });
    }

    private void deleteQuietly(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception ignored) {
            // ignore
        }
    }
}
