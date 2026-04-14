package cn.nexus.infrastructure.adapter.counter.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Thin Redis helper wrapper for Count Redis primitive operations.
 */
public class CountRedisOperations {

    private final StringRedisTemplate redisTemplate;

    public CountRedisOperations(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Map<String, Long> readObjectSnapshot(String key, CountRedisSchema schema) {
        return readSnapshot(key, schema);
    }

    public Map<String, Long> readUserSnapshot(String key, CountRedisSchema schema) {
        return readSnapshot(key, schema);
    }

    public void writeUserSnapshot(String key, Map<String, Long> values, CountRedisSchema schema) {
        writeSnapshot(key, values, schema);
    }

    public void writeObjectSnapshot(String key, Map<String, Long> values, CountRedisSchema schema) {
        writeSnapshot(key, values, schema);
    }

    public boolean readBitmapFact(String key, long offset) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().getBit(key, offset));
    }

    public boolean writeBitmapFact(String key, long offset, boolean desiredState) {
        Boolean previous = redisTemplate.opsForValue().setBit(key, offset, desiredState);
        return Boolean.TRUE.equals(previous);
    }

    public void addAggregationDelta(String bucketKey, String field, long delta) {
        redisTemplate.opsForHash().increment(bucketKey, field, delta);
    }

    public Map<String, Long> readAggregationBucket(String bucketKey) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(bucketKey);
        Map<String, Long> normalized = new LinkedHashMap<>();
        if (raw == null) {
            return normalized;
        }
        raw.forEach((field, value) -> normalized.put(String.valueOf(field), clampNonNegative(parseLong(value))));
        return normalized;
    }

    public void writeReplayCheckpoint(String checkpointKey, long checkpoint) {
        redisTemplate.opsForValue().set(checkpointKey, String.valueOf(Math.max(0L, checkpoint)));
    }

    public long readReplayCheckpoint(String checkpointKey) {
        return clampNonNegative(parseLong(redisTemplate.opsForValue().get(checkpointKey)));
    }

    public boolean tryAcquireRebuildLock(String key, long ttlSeconds) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Math.max(1L, ttlSeconds), TimeUnit.SECONDS));
    }

    public void releaseRebuildLock(String key) {
        redisTemplate.delete(key);
    }

    public boolean tryAcquireRateLimit(String key, long ttlSeconds) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Math.max(1L, ttlSeconds), TimeUnit.SECONDS));
    }

    private Map<String, Long> readSnapshot(String key, CountRedisSchema schema) {
        Map<String, Long> values = new LinkedHashMap<>();
        if (schema == null) {
            return values;
        }
        long[] decoded = CountRedisCodec.decodeSlots(
                CountRedisCodec.fromRedisValue(redisTemplate.opsForValue().get(key)),
                schema.slotCount());
        String[] fieldNames = schema.orderedFieldNames();
        for (int i = 0; i < fieldNames.length; i++) {
            values.put(fieldNames[i], decoded[i]);
        }
        return values;
    }

    private void writeSnapshot(String key, Map<String, Long> values, CountRedisSchema schema) {
        if (schema == null) {
            return;
        }
        long[] slots = new long[schema.slotCount()];
        String[] fieldNames = schema.orderedFieldNames();
        for (int i = 0; i < fieldNames.length; i++) {
            slots[i] = clampNonNegative(values == null ? null : values.get(fieldNames[i]));
        }
        redisTemplate.opsForValue().set(key, CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(slots, slots.length)));
    }

    private long parseLong(Object raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private long clampNonNegative(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private long clampNonNegative(long value) {
        return Math.max(0L, value);
    }
}
