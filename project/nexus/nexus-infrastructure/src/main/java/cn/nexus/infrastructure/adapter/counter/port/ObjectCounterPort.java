package cn.nexus.infrastructure.adapter.counter.port;

import cn.nexus.domain.counter.adapter.port.IObjectCounterPort;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisOperations;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 对象维度统一计数 Redis 实现。
 *
 * @author codex
 * @since 2026-04-02
 */
@Component
@RequiredArgsConstructor
public class ObjectCounterPort implements IObjectCounterPort {

    private static final long REBUILD_LOCK_SECONDS = 15L;
    private static final long REBUILD_RATE_LIMIT_SECONDS = 30L;

    private final StringRedisTemplate redisTemplate;

    @Override
    public long getCount(ObjectCounterTarget target) {
        if (target == null) {
            return 0L;
        }
        if (!isSupportedMetric(target)) {
            return 0L;
        }
        return snapshotValue(target);
    }

    @Override
    public Map<String, Long> batchGetCount(List<ObjectCounterTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(targets.size());
        for (ObjectCounterTarget target : targets) {
            keys.add(target == null ? null : CountRedisKeys.objectSnapshot(target));
        }
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
        } catch (Exception e) {
            values = null;
        }

        Map<String, Long> result = new HashMap<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            ObjectCounterTarget target = targets.get(i);
            if (target == null) {
                continue;
            }
            if (!isSupportedMetric(target)) {
                result.put(target.hashTag(), 0L);
                continue;
            }
            String raw = values != null && i < values.size() ? values.get(i) : null;
            result.put(target.hashTag(), snapshotValue(target, raw));
        }
        return result;
    }

    @Override
    public long increment(ObjectCounterTarget target, long delta) {
        if (target == null) {
            return 0L;
        }
        if (!isSupportedMetric(target)) {
            return 0L;
        }
        if (delta == 0) {
            return getCount(target);
        }
        long updated = Math.max(0L, snapshotValue(target) + delta);
        writeAggregationDelta(target, delta);
        writeSnapshotValue(target, updated);
        return updated;
    }

    @Override
    public void setCount(ObjectCounterTarget target, long count) {
        if (target == null) {
            return;
        }
        if (!isSupportedMetric(target)) {
            return;
        }
        writeSnapshotValue(target, Math.max(0L, count));
    }

    @Override
    public void evict(ObjectCounterTarget target) {
        if (target == null) {
            return;
        }
        redisTemplate.delete(CountRedisKeys.objectSnapshot(target));
    }

    private long snapshotValue(ObjectCounterTarget target) {
        return snapshotValue(target, null);
    }

    private long snapshotValue(ObjectCounterTarget target, String rawSnapshot) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null || target.getCounterType() == null) {
            return 0L;
        }
        if (!isSupportedMetric(target)) {
            return 0L;
        }
        CountRedisSchema schema = CountRedisSchema.forObject(target.getTargetType());
        if (schema == null) {
            return 0L;
        }
        String snapshotKey = CountRedisKeys.objectSnapshot(target);
        if (rawSnapshot == null) {
            rawSnapshot = redisTemplate.opsForValue().get(snapshotKey);
        }
        if (rawSnapshot == null || rawSnapshot.isBlank()) {
            return rebuildSnapshotValueIfSupported(target, snapshotKey, schema);
        }
        try {
            Map<String, Long> snapshot = decodeRawSnapshot(schema, rawSnapshot);
            Long value = snapshot.get(target.getCounterType().getCode());
            if (needsRebuild(target, value, snapshot, rawSnapshot)) {
                return rebuildSnapshotValueIfSupported(target, snapshotKey, schema);
            }
            return value == null ? 0L : Math.max(0L, value);
        } catch (Exception ignored) {
            return rebuildSnapshotValueIfSupported(target, snapshotKey, schema);
        }
    }

    private Map<String, Long> decodeRawSnapshot(CountRedisSchema schema, String rawSnapshot) {
        try {
            long[] decoded = cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec.decodeSlots(
                    cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec.fromRedisValue(rawSnapshot),
                    schema.slotCount());
            Map<String, Long> snapshot = new HashMap<>(schema.slotCount());
            String[] fieldNames = schema.orderedFieldNames();
            for (int i = 0; i < fieldNames.length; i++) {
                snapshot.put(fieldNames[i], decoded[i]);
            }
            return snapshot;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void writeSnapshotValue(ObjectCounterTarget target, long count) {
        if (target == null || target.getTargetType() == null || target.getCounterType() == null) {
            return;
        }
        if (!isSupportedMetric(target)) {
            return;
        }
        CountRedisSchema schema = CountRedisSchema.forObject(target.getTargetType());
        String key = CountRedisKeys.objectSnapshot(target);
        if (schema == null || key == null) {
            return;
        }
        if (schema.slotOf(target.getCounterType()) < 0) {
            return;
        }
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        Map<String, Long> snapshot = operations.readObjectSnapshot(key, schema);
        snapshot.put(target.getCounterType().getCode(), Math.max(0L, count));
        operations.writeObjectSnapshot(key, snapshot, schema);
    }

    private void writeAggregationDelta(ObjectCounterTarget target, long delta) {
        if (target == null || target.getTargetType() == null || target.getCounterType() == null || target.getTargetId() == null) {
            return;
        }
        if (target.getCounterType() != ObjectCounterType.LIKE) {
            return;
        }
        String bucketKey = CountRedisKeys.objectAggregationBucket(target.getTargetType(), target.getTargetId());
        if (bucketKey == null) {
            return;
        }
        int slot = CountRedisSchema.forObject(target.getTargetType()) == null
                ? -1
                : CountRedisSchema.forObject(target.getTargetType()).slotOf(target.getCounterType());
        if (slot < 0) {
            return;
        }
        new CountRedisOperations(redisTemplate).addAggregationDelta(bucketKey, String.valueOf(slot), delta);
    }

    private boolean needsRebuild(ObjectCounterTarget target, Long value, Map<String, Long> snapshot, String rawSnapshot) {
        CountRedisSchema schema = CountRedisSchema.forObject(target.getTargetType());
        if (schema == null || !supportsBitmapRebuild(target)) {
            return false;
        }
        byte[] payload = rawSnapshot == null ? null
                : cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec.fromRedisValue(rawSnapshot);
        return value == null
                || snapshot == null
                || snapshot.size() < schema.slotCount()
                || (payload != null && payload.length != schema.totalPayloadBytes());
    }

    private boolean supportsBitmapRebuild(ObjectCounterTarget target) {
        return target != null
                && target.getCounterType() == ObjectCounterType.LIKE
                && (target.getTargetType() == ReactionTargetTypeEnumVO.POST
                || target.getTargetType() == ReactionTargetTypeEnumVO.COMMENT);
    }

    private boolean isSupportedMetric(ObjectCounterTarget target) {
        return target != null
                && target.getCounterType() == ObjectCounterType.LIKE
                && (target.getTargetType() == ReactionTargetTypeEnumVO.POST
                || target.getTargetType() == ReactionTargetTypeEnumVO.COMMENT);
    }

    private long rebuildSnapshotValueIfSupported(ObjectCounterTarget target, String snapshotKey, CountRedisSchema schema) {
        if (!supportsBitmapRebuild(target) || snapshotKey == null || schema == null) {
            return 0L;
        }
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        String rateLimitKey = CountRedisKeys.objectRebuildRateLimit(target);
        if (rateLimitKey == null || !operations.tryAcquireRateLimit(rateLimitKey, REBUILD_RATE_LIMIT_SECONDS)) {
            return 0L;
        }
        String rebuildLockKey = CountRedisKeys.objectRebuildLock(target);
        if (rebuildLockKey == null || !operations.tryAcquireRebuildLock(rebuildLockKey, REBUILD_LOCK_SECONDS)) {
            return 0L;
        }
        try {
            long rebuiltCount = rebuildBitmapLikeCount(target);
            Map<String, Long> snapshot = new HashMap<>(operations.readObjectSnapshot(snapshotKey, schema));
            snapshot.put(target.getCounterType().getCode(), rebuiltCount);
            operations.writeObjectSnapshot(snapshotKey, snapshot, schema);
            clearAggregationOverlap(target);
            return rebuiltCount;
        } finally {
            operations.releaseRebuildLock(rebuildLockKey);
        }
    }

    private long rebuildBitmapLikeCount(ObjectCounterTarget target) {
        String pattern = CountRedisKeys.likeBitmapShardPattern(target);
        if (pattern == null) {
            return 0L;
        }
        Long total = redisTemplate.execute((RedisCallback<Long>) connection -> {
            if (connection == null) {
                return 0L;
            }
            long sum = 0L;
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(256).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    byte[] shardKey = cursor.next();
                    if (shardKey == null || shardKey.length == 0) {
                        continue;
                    }
                    Long shardCount = connection.stringCommands().bitCount(shardKey);
                    if (shardCount != null && shardCount > 0) {
                        sum += shardCount;
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("scan bitmap like shards failed, pattern=" + pattern, e);
            }
            return Math.max(0L, sum);
        });
        return total == null ? 0L : Math.max(0L, total);
    }

    private void clearAggregationOverlap(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null || target.getCounterType() == null) {
            return;
        }
        String aggregationKey = CountRedisKeys.objectAggregationBucket(target.getTargetType(), target.getTargetId());
        CountRedisSchema schema = CountRedisSchema.forObject(target.getTargetType());
        int slot = schema == null ? -1 : schema.slotOf(target.getCounterType());
        if (aggregationKey == null || slot < 0) {
            return;
        }
        redisTemplate.opsForHash().delete(aggregationKey, String.valueOf(slot));
    }
}
