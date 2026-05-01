package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IFeedCounterSideEffectPort;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardStatRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void applyPostCounterDelta(Long postId, ObjectCounterType metric, long delta) {
        if (postId == null || metric == null || delta == 0) {
            return;
        }
        String counterField = counterField(metric);
        if (counterField == null) {
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
                        continue;
                    }
                    updatePageJson(pageKey, postId, counterField, delta);
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

    private void updatePageJson(String pageKey, Long postId, String counterField, long delta) {
        if (pageKey == null || pageKey.isBlank() || postId == null) {
            return;
        }
        try {
            Long pageTtlSeconds = stringRedisTemplate.getExpire(pageKey, TimeUnit.SECONDS);
            String raw = stringRedisTemplate.opsForValue().get(pageKey);
            if (raw == null || raw.isBlank()) {
                return;
            }
            JsonNode root = objectMapper.readTree(raw);
            boolean changed = applyCounterDelta(root, postId, counterField, delta);
            if (!changed) {
                return;
            }
            if (pageTtlSeconds != null && pageTtlSeconds > 0) {
                stringRedisTemplate.opsForValue().set(pageKey, objectMapper.writeValueAsString(root), pageTtlSeconds, TimeUnit.SECONDS);
            } else if (pageTtlSeconds != null && pageTtlSeconds == -1L) {
                stringRedisTemplate.opsForValue().set(pageKey, objectMapper.writeValueAsString(root));
            }
        } catch (Exception e) {
            log.warn("update feed page json counter failed, pageKey={}, postId={}, field={}, delta={}",
                    pageKey, postId, counterField, delta, e);
        }
    }

    private boolean applyCounterDelta(JsonNode node, Long postId, String counterField, long delta) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            boolean changed = false;
            for (JsonNode item : node) {
                changed |= applyCounterDelta(item, postId, counterField, delta);
            }
            return changed;
        }
        if (!node.isObject()) {
            return false;
        }
        ObjectNode object = (ObjectNode) node;
        boolean changed = false;
        if (matchesPost(object, postId) && object.has(counterField)) {
            long next = Math.max(0L, object.path(counterField).asLong(0L) + delta);
            object.put(counterField, next);
            changed = true;
        }
        Iterator<JsonNode> children = object.elements();
        while (children.hasNext()) {
            JsonNode child = children.next();
            if (child instanceof ArrayNode || child instanceof ObjectNode) {
                changed |= applyCounterDelta(child, postId, counterField, delta);
            }
        }
        return changed;
    }

    private String counterField(ObjectCounterType metric) {
        if (metric == ObjectCounterType.LIKE) {
            return "likeCount";
        }
        if (metric == ObjectCounterType.FAV) {
            return "favoriteCount";
        }
        return null;
    }

    private boolean matchesPost(ObjectNode object, Long postId) {
        return matchesField(object, "postId", postId)
                || matchesField(object, "id", postId)
                || matchesField(object, "contentId", postId)
                || matchesField(object, "entityId", postId);
    }

    private boolean matchesField(ObjectNode object, String field, Long postId) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isNumber()) {
            return value.asLong() == postId;
        }
        return String.valueOf(postId).equals(value.asText());
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
