package cn.nexus.infrastructure.adapter.counter.service;

import cn.nexus.domain.counter.adapter.port.ICounterEventProducer;
import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import cn.nexus.domain.counter.model.event.CounterEvent;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.PostActionResultVO;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ObjectCounterService implements IObjectCounterService {

    private static final String POST = "post";
    private static final long REBUILD_LOCK_SECONDS = 15L;
    private static final long REBUILD_RATE_LIMIT_SECONDS = 30L;
    private static final long REBUILD_BACKOFF_SECONDS = 120L;

    private final StringRedisTemplate redisTemplate;
    private final ICounterEventProducer counterEventProducer;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public PostActionResultVO likePost(Long postId, Long userId) {
        return togglePostMetric(postId, userId, ObjectCounterType.LIKE, true);
    }

    @Override
    public PostActionResultVO unlikePost(Long postId, Long userId) {
        return togglePostMetric(postId, userId, ObjectCounterType.LIKE, false);
    }

    @Override
    public PostActionResultVO favPost(Long postId, Long userId) {
        return togglePostMetric(postId, userId, ObjectCounterType.FAV, true);
    }

    @Override
    public PostActionResultVO unfavPost(Long postId, Long userId) {
        return togglePostMetric(postId, userId, ObjectCounterType.FAV, false);
    }

    @Override
    public boolean isPostLiked(Long postId, Long userId) {
        return readBitmap(ObjectCounterType.LIKE, postId, userId);
    }

    @Override
    public boolean isPostFaved(Long postId, Long userId) {
        return readBitmap(ObjectCounterType.FAV, postId, userId);
    }

    @Override
    public Map<String, Long> getPostCounts(Long postId, List<ObjectCounterType> metrics) {
        Map<String, Long> values = new LinkedHashMap<>();
        List<ObjectCounterType> activeMetrics = activeMetrics(metrics);
        if (postId == null || activeMetrics.isEmpty()) {
            return values;
        }
        CountRedisSchema schema = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST);
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        byte[] raw = operations.readSnapshotPayload(CountRedisKeys.objectSnapshot(ReactionTargetTypeEnumVO.POST, postId));
        boolean malformed = schema == null || raw.length != schema.totalPayloadBytes();
        if (malformed) {
            raw = rebuildSnapshotIfPossible(postId, activeMetrics);
            malformed = schema == null || raw.length != schema.totalPayloadBytes();
        }
        long[] decoded = malformed ? new long[0] : CountRedisCodec.decodeSlots(raw, schema.slotCount());
        for (ObjectCounterType metric : activeMetrics) {
            values.put(metric.getCode(), valueAt(decoded, schema, metric, malformed));
        }
        return values;
    }

    @Override
    public Map<Long, Map<String, Long>> getPostCountsBatch(List<Long> postIds, List<ObjectCounterType> metrics) {
        Map<Long, Map<String, Long>> out = new LinkedHashMap<>();
        List<ObjectCounterType> activeMetrics = activeMetrics(metrics);
        if (postIds == null || postIds.isEmpty() || activeMetrics.isEmpty()) {
            return out;
        }
        CountRedisSchema schema = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST);
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        for (Long postId : postIds) {
            byte[] raw = operations.readSnapshotPayload(CountRedisKeys.objectSnapshot(ReactionTargetTypeEnumVO.POST, postId));
            boolean malformed = schema == null || raw.length != schema.totalPayloadBytes();
            long[] decoded = malformed ? new long[0] : CountRedisCodec.decodeSlots(raw, schema.slotCount());
            Map<String, Long> values = new LinkedHashMap<>();
            for (ObjectCounterType metric : activeMetrics) {
                values.put(metric.getCode(), valueAt(decoded, schema, metric, malformed));
            }
            out.put(postId, values);
        }
        return out;
    }

    private PostActionResultVO togglePostMetric(Long postId, Long userId, ObjectCounterType metric, boolean desiredState) {
        if (postId == null || userId == null || metric == null) {
            return PostActionResultVO.builder().build();
        }
        long chunk = userId / CountRedisKeys.CHUNK_SIZE;
        long offset = userId % CountRedisKeys.CHUNK_SIZE;
        String bitmapKey = CountRedisKeys.bitmapShard(metric, ReactionTargetTypeEnumVO.POST, postId, chunk);
        Boolean previous = redisTemplate.opsForValue().setBit(bitmapKey, offset, desiredState);
        boolean changed = desiredState ? !Boolean.TRUE.equals(previous) : Boolean.TRUE.equals(previous);
        if (changed) {
            publishEvents(postId, userId, metric, desiredState ? 1L : -1L);
        }
        return actionSnapshot(postId, userId, changed);
    }

    private void publishEvents(Long postId, Long userId, ObjectCounterType metric, long delta) {
        CountRedisSchema schema = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST);
        int slot = schema == null ? -1 : schema.slotOf(metric);
        long now = System.currentTimeMillis();
        counterEventProducer.publish(CounterDeltaEvent.builder()
                .targetType(POST)
                .targetId(postId)
                .metric(metric.getCode())
                .slot(slot)
                .actorUserId(userId)
                .delta(delta)
                .tsMs(now)
                .build());
        applicationEventPublisher.publishEvent(CounterEvent.builder()
                .targetType(POST)
                .targetId(postId)
                .metric(metric.getCode())
                .slot(slot)
                .actorUserId(userId)
                .delta(delta)
                .tsMs(now)
                .build());
    }

    private PostActionResultVO actionSnapshot(Long postId, Long userId, boolean changed) {
        Map<String, Long> counts = getPostCounts(postId, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));
        return PostActionResultVO.builder()
                .changed(changed)
                .liked(isPostLiked(postId, userId))
                .faved(isPostFaved(postId, userId))
                .likeCount(counts.getOrDefault(ObjectCounterType.LIKE.getCode(), 0L))
                .favoriteCount(counts.getOrDefault(ObjectCounterType.FAV.getCode(), 0L))
                .build();
    }

    private boolean readBitmap(ObjectCounterType metric, Long postId, Long userId) {
        if (metric == null || postId == null || userId == null) {
            return false;
        }
        long chunk = userId / CountRedisKeys.CHUNK_SIZE;
        long offset = userId % CountRedisKeys.CHUNK_SIZE;
        String key = CountRedisKeys.bitmapShard(metric, ReactionTargetTypeEnumVO.POST, postId, chunk);
        return Boolean.TRUE.equals(redisTemplate.opsForValue().getBit(key, offset));
    }

    private byte[] rebuildSnapshotIfPossible(Long postId, List<ObjectCounterType> requestedMetrics) {
        ObjectCounterTarget target = ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .build();
        if (!canAttemptRebuild(target)) {
            return new byte[0];
        }
        String lockKey = CountRedisKeys.objectRebuildLock(target);
        if (lockKey == null || !Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", REBUILD_LOCK_SECONDS, TimeUnit.SECONDS))) {
            escalateRebuildBackoff(target);
            return new byte[0];
        }
        try {
            CountRedisSchema schema = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST);
            if (schema == null) {
                return new byte[0];
            }
            Map<String, Long> snapshot = new HashMap<>();
            for (String field : schema.orderedFieldNames()) {
                snapshot.put(field, 0L);
            }
            for (ObjectCounterType metric : List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV)) {
                snapshot.put(metric.getCode(), rebuildBitmapCount(metric, postId));
            }
            byte[] payload = CountRedisCodec.encodeSlots(toSlots(snapshot, schema), schema.slotCount());
            new CountRedisOperations(redisTemplate).writeSnapshotPayload(
                    CountRedisKeys.objectSnapshot(ReactionTargetTypeEnumVO.POST, postId), payload);
            clearAggregationOverlap(postId, requestedMetrics);
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
            long backoffUntil = parseLong(redisTemplate.opsForValue().get(backoffKey));
            if (backoffUntil > System.currentTimeMillis()) {
                return false;
            }
        }
        String rateLimitKey = CountRedisKeys.objectRebuildRateLimit(target);
        boolean allowed = rateLimitKey != null && Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(rateLimitKey, "1", REBUILD_RATE_LIMIT_SECONDS, TimeUnit.SECONDS));
        if (!allowed) {
            escalateRebuildBackoff(target);
        }
        return allowed;
    }

    private long rebuildBitmapCount(ObjectCounterType metric, Long postId) {
        long sum = 0L;
        for (String shardKey : scanBitmapShardKeys(metric, postId)) {
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

    private Set<String> scanBitmapShardKeys(ObjectCounterType metric, Long postId) {
        String pattern = "bm:" + metric.getCode() + ":post:" + postId + ":*";
        Set<String> keys = redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> result = new java.util.HashSet<>();
            if (connection == null) {
                return result;
            }
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(256).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    byte[] keyBytes = cursor.next();
                    if (keyBytes != null && keyBytes.length > 0) {
                        result.add(new String(keyBytes, StandardCharsets.UTF_8));
                    }
                }
            }
            return result;
        });
        return keys == null ? Set.of() : keys;
    }

    private void clearAggregationOverlap(Long postId, List<ObjectCounterType> metrics) {
        String aggregationKey = CountRedisKeys.objectAggregationBucket(ReactionTargetTypeEnumVO.POST, postId);
        if (aggregationKey == null) {
            return;
        }
        CountRedisSchema schema = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST);
        for (ObjectCounterType metric : activeMetrics(metrics)) {
            int slot = schema == null ? -1 : schema.slotOf(metric);
            if (slot >= 0) {
                redisTemplate.opsForHash().delete(aggregationKey, String.valueOf(slot));
            }
        }
    }

    private List<ObjectCounterType> activeMetrics(List<ObjectCounterType> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return List.of();
        }
        List<ObjectCounterType> out = new ArrayList<>();
        for (ObjectCounterType metric : metrics) {
            if ((metric == ObjectCounterType.LIKE || metric == ObjectCounterType.FAV) && !out.contains(metric)) {
                out.add(metric);
            }
        }
        return out;
    }

    private long valueAt(long[] decoded, CountRedisSchema schema, ObjectCounterType metric, boolean malformed) {
        if (malformed || schema == null) {
            return 0L;
        }
        int slot = schema.slotOf(metric);
        return slot < 0 || slot >= decoded.length ? 0L : Math.max(0L, decoded[slot]);
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
        if (backoffKey != null) {
            redisTemplate.delete(backoffKey);
        }
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
}
