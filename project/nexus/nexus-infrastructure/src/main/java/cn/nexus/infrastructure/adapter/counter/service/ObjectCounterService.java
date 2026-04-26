package cn.nexus.infrastructure.adapter.counter.service;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.adapter.port.ICounterEventProducer;
import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisOperations;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisSchema;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed object counter service with bitmap truth and snapshot reads.
 */
@Service
@RequiredArgsConstructor
public class ObjectCounterService implements IObjectCounterService {

    private static final long REBUILD_LOCK_SECONDS = 15L;
    private static final long REBUILD_RATE_LIMIT_SECONDS = 30L;
    private static final long REBUILD_BACKOFF_SECONDS = 120L;
    private static final int AGG_SHARDS = 64;

    private final StringRedisTemplate redisTemplate;
    private final ICounterEventProducer counterEventProducer;

    @Override
    public boolean like(ReactionTargetTypeEnumVO targetType, Long targetId, Long userId) {
        return toggleLike(targetType, targetId, userId, true);
    }

    @Override
    public boolean unlike(ReactionTargetTypeEnumVO targetType, Long targetId, Long userId) {
        return toggleLike(targetType, targetId, userId, false);
    }

    @Override
    public boolean isLiked(ReactionTargetTypeEnumVO targetType, Long targetId, Long userId) {
        if (!isValidLikeTarget(targetType, targetId, userId)) {
            return false;
        }
        long shard = userId / CountRedisKeys.CHUNK_SIZE;
        long offset = userId % CountRedisKeys.CHUNK_SIZE;
        String key = CountRedisKeys.likeBitmapShard(targetType, targetId, shard);
        return Boolean.TRUE.equals(redisTemplate.opsForValue().getBit(key, offset));
    }

    @Override
    public Map<String, Long> getCounts(ReactionTargetTypeEnumVO targetType, Long targetId, List<ObjectCounterType> metrics) {
        Map<String, Long> values = new LinkedHashMap<>();
        if (targetType == null || targetId == null || metrics == null || metrics.isEmpty()) {
            return values;
        }
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        byte[] raw = operations.readSnapshotPayload(CountRedisKeys.objectSnapshot(targetType, targetId));
        CountRedisSchema schema = CountRedisSchema.forObject(targetType);
        boolean malformed = schema == null || raw.length != schema.totalPayloadBytes();
        if (malformed && supportsBitmapRebuild(targetType)) {
            raw = rebuildSnapshotIfPossible(targetType, targetId);
            malformed = schema == null || raw.length != schema.totalPayloadBytes();
        }
        long[] decoded = malformed ? new long[0] : CountRedisCodec.decodeSlots(raw, schema.slotCount());
        for (ObjectCounterType metric : metrics) {
            if (metric == null || metric != ObjectCounterType.LIKE) {
                continue;
            }
            if (malformed) {
                values.put(metric.getCode(), 0L);
                continue;
            }
            int slot = schema.slotOf(metric);
            values.put(metric.getCode(), slot < 0 || slot >= decoded.length ? 0L : decoded[slot]);
        }
        return values;
    }

    @Override
    public Map<Long, Map<String, Long>> getCountsBatch(ReactionTargetTypeEnumVO targetType,
                                                       List<Long> targetIds,
                                                       List<ObjectCounterType> metrics) {
        Map<Long, Map<String, Long>> out = new LinkedHashMap<>();
        if (targetType == null || targetIds == null || targetIds.isEmpty() || metrics == null || metrics.isEmpty()) {
            return out;
        }

        List<String> keys = new ArrayList<>(targetIds.size());
        for (Long targetId : targetIds) {
            keys.add(CountRedisKeys.objectSnapshot(targetType, targetId));
        }
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);

        for (int i = 0; i < targetIds.size(); i++) {
            Long targetId = targetIds.get(i);
            byte[] raw = operations.readSnapshotPayload(keys.get(i));
            Map<String, Long> values = new LinkedHashMap<>();
            CountRedisSchema schema = CountRedisSchema.forObject(targetType);
            boolean malformed = schema == null || raw.length != schema.totalPayloadBytes();
            long[] decoded = malformed ? new long[0] : CountRedisCodec.decodeSlots(raw, schema.slotCount());
            for (ObjectCounterType metric : metrics) {
                if (metric == null || metric != ObjectCounterType.LIKE) {
                    continue;
                }
                if (malformed) {
                    values.put(metric.getCode(), 0L);
                    continue;
                }
                int slot = schema.slotOf(metric);
                values.put(metric.getCode(), slot < 0 || slot >= decoded.length ? 0L : decoded[slot]);
            }
            out.put(targetId, values);
        }
        return out;
    }

    private boolean toggleLike(ReactionTargetTypeEnumVO targetType, Long targetId, Long userId, boolean desiredState) {
        if (!isValidLikeTarget(targetType, targetId, userId)) {
            return false;
        }
        long shard = userId / CountRedisKeys.CHUNK_SIZE;
        long offset = userId % CountRedisKeys.CHUNK_SIZE;
        String bitmapKey = CountRedisKeys.likeBitmapShard(targetType, targetId, shard);
        Boolean previous = redisTemplate.opsForValue().setBit(bitmapKey, offset, desiredState);
        boolean changed = desiredState ? !Boolean.TRUE.equals(previous) : Boolean.TRUE.equals(previous);
        if (!changed) {
            return false;
        }
        int slot = CountRedisSchema.forObject(targetType).slotOf(ObjectCounterType.LIKE);
        registerBitmapShard(targetType, targetId, shard);
        counterEventProducer.publish(CounterDeltaEvent.builder()
                .entityType(targetType)
                .entityId(targetId)
                .metric(ObjectCounterType.LIKE)
                .idx(slot)
                .userId(userId)
                .delta(desiredState ? 1L : -1L)
                .build());
        return true;
    }

    private boolean isValidLikeTarget(ReactionTargetTypeEnumVO targetType, Long targetId, Long userId) {
        return targetType != null
                && targetId != null
                && userId != null
                && (targetType == ReactionTargetTypeEnumVO.POST || targetType == ReactionTargetTypeEnumVO.COMMENT);
    }

    private boolean supportsBitmapRebuild(ReactionTargetTypeEnumVO targetType) {
        return targetType == ReactionTargetTypeEnumVO.POST || targetType == ReactionTargetTypeEnumVO.COMMENT;
    }

    private void registerBitmapShard(ReactionTargetTypeEnumVO targetType, Long targetId, long shard) {
        String indexKey = CountRedisKeys.likeBitmapShardIndex(targetType, targetId);
        if (indexKey == null) {
            return;
        }
        redisTemplate.opsForSet().add(indexKey, String.valueOf(shard));
    }

    private byte[] rebuildSnapshotIfPossible(ReactionTargetTypeEnumVO targetType, Long targetId) {
        ObjectCounterTarget target = ObjectCounterTarget.builder()
                .targetType(targetType)
                .targetId(targetId)
                .counterType(ObjectCounterType.LIKE)
                .build();
        if (!canAttemptRebuild(target)) {
            return new byte[0];
        }
        String lockKey = CountRedisKeys.objectRebuildLock(target);
        if (lockKey == null || !Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                lockKey, "1", REBUILD_LOCK_SECONDS, TimeUnit.SECONDS))) {
            escalateRebuildBackoff(target);
            return new byte[0];
        }
        try {
            long rebuiltLike = rebuildBitmapLikeCount(target);
            CountRedisSchema schema = CountRedisSchema.forObject(targetType);
            if (schema == null) {
                escalateRebuildBackoff(target);
                return new byte[0];
            }
            Map<String, Long> snapshot = new HashMap<>();
            for (String field : schema.orderedFieldNames()) {
                snapshot.put(field, 0L);
            }
            snapshot.put(ObjectCounterType.LIKE.getCode(), Math.max(0L, rebuiltLike));
            byte[] payload = CountRedisCodec.encodeSlots(toSlots(snapshot, schema), schema.slotCount());
            new CountRedisOperations(redisTemplate).writeSnapshotPayload(CountRedisKeys.objectSnapshot(targetType, targetId), payload);
            clearAggregationOverlap(target);
            resetRebuildBackoff(target);
            return payload;
        } catch (Exception e) {
            escalateRebuildBackoff(target);
            return new byte[0];
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private boolean canAttemptRebuild(ObjectCounterTarget target) {
        String backoffKey = CountRedisKeys.objectRebuildBackoff(target);
        if (backoffKey != null) {
            String backoffRaw = redisTemplate.opsForValue().get(backoffKey);
            long backoffUntil = parseLong(backoffRaw);
            if (backoffUntil > System.currentTimeMillis()) {
                return false;
            }
        }
        String rateLimitKey = CountRedisKeys.objectRebuildRateLimit(target);
        boolean allowed = rateLimitKey != null && Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                rateLimitKey, "1", REBUILD_RATE_LIMIT_SECONDS, TimeUnit.SECONDS));
        if (!allowed) {
            escalateRebuildBackoff(target);
        }
        return allowed;
    }

    private long rebuildBitmapLikeCount(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null) {
            return 0L;
        }
        Set<String> shards = redisTemplate.opsForSet().members(
                CountRedisKeys.likeBitmapShardIndex(target.getTargetType(), target.getTargetId()));
        if (shards == null || shards.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (String shard : shards) {
            Long shardId = parseShard(shard);
            if (shardId == null) {
                continue;
            }
            String shardKey = CountRedisKeys.likeBitmapShard(target.getTargetType(), target.getTargetId(), shardId);
            Long shardCount = redisTemplate.execute((RedisCallback<Long>) connection -> {
                if (connection == null) {
                    return 0L;
                }
                return connection.stringCommands().bitCount(shardKey.getBytes(StandardCharsets.UTF_8));
            });
            if (shardCount != null && shardCount > 0L) {
                sum += shardCount;
            }
        }
        return Math.max(0L, sum);
    }

    private void clearAggregationOverlap(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null || target.getCounterType() == null) {
            return;
        }
        CountRedisSchema schema = CountRedisSchema.forObject(target.getTargetType());
        int slot = schema == null ? -1 : schema.slotOf(target.getCounterType());
        if (slot < 0) {
            return;
        }
        for (int shard = 0; shard < AGG_SHARDS; shard++) {
            String aggregationKey = CountRedisKeys.objectAggregationBucket(
                    target.getTargetType(),
                    target.getTargetId(),
                    shard);
            if (aggregationKey != null) {
                redisTemplate.opsForHash().delete(aggregationKey, String.valueOf(slot));
            }
        }
    }

    private void escalateRebuildBackoff(ObjectCounterTarget target) {
        String backoffKey = CountRedisKeys.objectRebuildBackoff(target);
        if (backoffKey == null) {
            return;
        }
        long until = System.currentTimeMillis() + REBUILD_BACKOFF_SECONDS * 1000L;
        redisTemplate.opsForValue().set(backoffKey, String.valueOf(until), REBUILD_BACKOFF_SECONDS, TimeUnit.SECONDS);
    }

    private void resetRebuildBackoff(ObjectCounterTarget target) {
        String backoffKey = CountRedisKeys.objectRebuildBackoff(target);
        if (backoffKey == null) {
            return;
        }
        redisTemplate.delete(backoffKey);
    }

    private long[] toSlots(Map<String, Long> values, CountRedisSchema schema) {
        long[] slots = new long[schema.slotCount()];
        String[] fieldNames = schema.orderedFieldNames();
        for (int i = 0; i < fieldNames.length; i++) {
            Long raw = values == null ? null : values.get(fieldNames[i]);
            slots[i] = raw == null ? 0L : Math.max(0L, raw);
        }
        return slots;
    }

    private long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private Long parseShard(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long shard = Long.parseLong(raw.trim());
            return shard < 0L ? null : shard;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}
