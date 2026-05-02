package cn.nexus.infrastructure.adapter.counter.support;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Thin Redis helper wrapper for Count Redis primitive operations.
 */
public class CountRedisOperations {

    private static final DefaultRedisScript<Long> INCREMENT_SNAPSHOT_SLOT_SCRIPT = new DefaultRedisScript<>(
            "local key = KEYS[1]; "
                    + "local slot = tonumber(ARGV[1]); "
                    + "local delta = tonumber(ARGV[2]); "
                    + "local slots = tonumber(ARGV[3]); "
                    + "local width = 4; "
                    + "local len = slots * width; "
                    + "local raw = redis.call('GET', key); "
                    + "if (not raw) or string.len(raw) ~= len then raw = string.rep(string.char(0), len); end; "
                    + "local start = slot * width + 1; "
                    + "local b1, b2, b3, b4 = string.byte(raw, start, start + 3); "
                    + "local current = b1 * 16777216 + b2 * 65536 + b3 * 256 + b4; "
                    + "local next = current + delta; "
                    + "if next < 0 then next = 0; end; "
                    + "if next > 4294967295 then next = 4294967295; end; "
                    + "local n = next; "
                    + "local nb4 = n % 256; n = math.floor(n / 256); "
                    + "local nb3 = n % 256; n = math.floor(n / 256); "
                    + "local nb2 = n % 256; n = math.floor(n / 256); "
                    + "local nb1 = n % 256; "
                    + "local updated = string.sub(raw, 1, start - 1) "
                    + ".. string.char(nb1, nb2, nb3, nb4) "
                    + ".. string.sub(raw, start + width); "
                    + "redis.call('SET', key, updated); "
                    + "return next;",
            Long.class);

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

    public byte[] readSnapshotPayload(String key) {
        if (key == null || key.isBlank()) {
            return new byte[0];
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] payload = redisTemplate.execute((RedisCallback<byte[]>) connection -> {
            if (connection == null || connection.stringCommands() == null) {
                return null;
            }
            return connection.stringCommands().get(keyBytes);
        });
        return CountRedisCodec.fromRedisValue(payload);
    }

    public void writeSnapshotPayload(String key, byte[] payload) {
        if (key == null || key.isBlank()) {
            return;
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = CountRedisCodec.toRedisValue(payload);
        redisTemplate.execute((RedisCallback<Boolean>) connection -> {
            if (connection == null || connection.stringCommands() == null) {
                return false;
            }
            connection.stringCommands().set(keyBytes, valueBytes);
            return true;
        });
    }

    public long incrementSnapshotSlot(String key, int slot, long delta, CountRedisSchema schema) {
        if (key == null || key.isBlank() || schema == null || slot < 0 || slot >= schema.slotCount()) {
            return 0L;
        }
        Long updated = redisTemplate.execute(
                INCREMENT_SNAPSHOT_SLOT_SCRIPT,
                List.of(key),
                String.valueOf(slot),
                String.valueOf(delta),
                String.valueOf(schema.slotCount()));
        return updated == null ? 0L : Math.max(0L, updated);
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
                readSnapshotPayload(key),
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
        writeSnapshotPayload(key, CountRedisCodec.encodeSlots(slots, slots.length));
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
