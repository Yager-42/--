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
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ObjectCounterService implements IObjectCounterService {

    private static final String POST = "post";
    private static final long REBUILD_LOCK_SECONDS = 300L;
    private static final int ACTION_LOCK_RETRY_ATTEMPTS = 50;
    private static final long ACTION_LOCK_RETRY_SLEEP_MS = 10L;
    private static final long REBUILD_RATE_LIMIT_SECONDS = 30L;
    private static final long REBUILD_BACKOFF_SECONDS = 120L;
    private static final long OBJECT_COUNTER_SAMPLE_CHECK_TTL_SECONDS = 300L;
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('DEL', KEYS[1]); "
                    + "else return 0; end",
            Long.class);
    private static final DefaultRedisScript<Long> FINALIZE_REBUILD_SCRIPT = new DefaultRedisScript<>(
            "local slots = tonumber(ARGV[1]); "
                    + "local watermarkPrefix = ARGV[2]; "
                    + "local watermark = ARGV[3]; "
                    + "local clearCount = tonumber(ARGV[4]) or 0; "
                    + "local width = 4; "
                    + "local len = slots * width; "
                    + "local raw = redis.call('GET', KEYS[1]); "
                    + "if (not raw) or string.len(raw) ~= len then raw = string.rep(string.char(0), len); end; "
                    + "local writeStart = 5 + clearCount; "
                    + "local i = writeStart; "
                    + "while i <= #ARGV do "
                    + "  local slot = tonumber(ARGV[i]); "
                    + "  local value = tonumber(ARGV[i + 1]) or 0; "
                    + "  if value < 0 then value = 0; end; "
                    + "  if value > 4294967295 then value = 4294967295; end; "
                    + "  if slot and slot >= 0 and slot < slots then "
                    + "  local x = value; "
                    + "  local b4 = x % 256; x = math.floor(x / 256); "
                    + "  local b3 = x % 256; x = math.floor(x / 256); "
                    + "  local b2 = x % 256; x = math.floor(x / 256); "
                    + "  local b1 = x % 256; "
                    + "  local start = slot * width + 1; "
                    + "  raw = string.sub(raw, 1, start - 1) "
                    + "    .. string.char(b1, b2, b3, b4) "
                    + "    .. string.sub(raw, start + width); "
                    + "  end; "
                    + "  i = i + 2; "
                    + "end; "
                    + "for j = 1, clearCount do "
                    + "  local clearSlot = tonumber(ARGV[4 + j]); "
                    + "  if clearSlot and clearSlot >= 0 and clearSlot < slots then "
                    + "    redis.call('HDEL', KEYS[2], tostring(clearSlot)); "
                    + "    redis.call('SET', watermarkPrefix .. ':' .. tostring(clearSlot), watermark); "
                    + "  end; "
                    + "end; "
                    + "redis.call('SET', KEYS[1], raw); "
                    + "return 1;",
            Long.class);

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
        } else {
            raw = rebuildSnapshotIfSampleMismatch(postId, activeMetrics, raw, schema);
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
        ObjectCounterTarget target = ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .build();
        String lockKey = CountRedisKeys.objectRebuildLock(target);
        String lockToken = acquireObjectCounterLockWithWait(lockKey, ACTION_LOCK_RETRY_ATTEMPTS, ACTION_LOCK_RETRY_SLEEP_MS);
        if (lockToken == null) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "object counter lock unavailable");
        }
        boolean changed = false;
        try {
            changed = mutateBitmapAndPublish(postId, userId, metric, desiredState);
        } finally {
            releaseObjectCounterLock(lockKey, lockToken);
        }
        return actionSnapshot(postId, userId, changed);
    }

    private boolean mutateBitmapAndPublish(Long postId, Long userId, ObjectCounterType metric, boolean desiredState) {
        long chunk = userId / CountRedisKeys.CHUNK_SIZE;
        long offset = userId % CountRedisKeys.CHUNK_SIZE;
        String bitmapKey = CountRedisKeys.bitmapShard(metric, ReactionTargetTypeEnumVO.POST, postId, chunk);
        Boolean previous = redisTemplate.opsForValue().setBit(bitmapKey, offset, desiredState);
        boolean changed = desiredState ? !Boolean.TRUE.equals(previous) : Boolean.TRUE.equals(previous);
        if (changed) {
            publishEvents(postId, userId, metric, desiredState ? 1L : -1L);
        }
        return changed;
    }

    private void publishEvents(Long postId, Long userId, ObjectCounterType metric, long delta) {
        CountRedisSchema schema = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST);
        int slot = schema == null ? -1 : schema.slotOf(metric);
        long now = redisNowMs();
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
        Map<String, Long> counts = readPostCountsSnapshotOnly(postId, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));
        return PostActionResultVO.builder()
                .changed(changed)
                .liked(isPostLiked(postId, userId))
                .faved(isPostFaved(postId, userId))
                .likeCount(counts.getOrDefault(ObjectCounterType.LIKE.getCode(), 0L))
                .favoriteCount(counts.getOrDefault(ObjectCounterType.FAV.getCode(), 0L))
                .build();
    }

    private Map<String, Long> readPostCountsSnapshotOnly(Long postId, List<ObjectCounterType> metrics) {
        Map<String, Long> values = new LinkedHashMap<>();
        List<ObjectCounterType> activeMetrics = activeMetrics(metrics);
        if (postId == null || activeMetrics.isEmpty()) {
            return values;
        }
        CountRedisSchema schema = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST);
        byte[] raw = new CountRedisOperations(redisTemplate)
                .readSnapshotPayload(CountRedisKeys.objectSnapshot(ReactionTargetTypeEnumVO.POST, postId));
        boolean malformed = schema == null || raw.length != schema.totalPayloadBytes();
        long[] decoded = malformed ? new long[0] : CountRedisCodec.decodeSlots(raw, schema.slotCount());
        for (ObjectCounterType metric : activeMetrics) {
            values.put(metric.getCode(), valueAt(decoded, schema, metric, malformed));
        }
        return values;
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
        String lockToken = acquireObjectCounterLock(lockKey);
        if (lockToken == null) {
            escalateRebuildBackoff(target);
            return new byte[0];
        }
        try {
            long watermark = redisNowMs();
            CountRedisSchema schema = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST);
            if (schema == null) {
                return new byte[0];
            }
            Map<String, Long> snapshot = new HashMap<>();
            for (String field : schema.orderedFieldNames()) {
                snapshot.put(field, 0L);
            }
            byte[] existing = new CountRedisOperations(redisTemplate)
                    .readSnapshotPayload(CountRedisKeys.objectSnapshot(ReactionTargetTypeEnumVO.POST, postId));
            boolean creatingSnapshot = existing.length != schema.totalPayloadBytes();
            List<ObjectCounterType> rebuildMetrics = creatingSnapshot
                    ? List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV)
                    : activeMetrics(requestedMetrics);
            for (ObjectCounterType metric : rebuildMetrics) {
                snapshot.put(metric.getCode(), rebuildBitmapCount(metric, postId));
            }
            long[] slots = toSlots(snapshot, schema);
            byte[] payload = CountRedisCodec.encodeSlots(slots, schema.slotCount());
            if (!finalizeRebuildSnapshot(postId, target, schema, slots, watermark,
                    rebuildMetrics, rebuildMetrics)) {
                throw new IllegalStateException("object counter rebuild finalize failed, postId=" + postId);
            }
            resetRebuildBackoff(target);
            return payload;
        } catch (Exception e) {
            escalateRebuildBackoff(target);
            return new byte[0];
        } finally {
            releaseObjectCounterLock(lockKey, lockToken);
        }
    }

    private byte[] rebuildSnapshotIfSampleMismatch(Long postId, List<ObjectCounterType> metrics,
                                                   byte[] raw, CountRedisSchema schema) {
        if (postId == null || schema == null || raw == null || raw.length != schema.totalPayloadBytes()) {
            return raw == null ? new byte[0] : raw;
        }
        String checkKey = CountRedisKeys.objectCounterSampleCheck(ReactionTargetTypeEnumVO.POST, postId);
        Boolean shouldCheck = checkKey != null && redisTemplate.opsForValue()
                .setIfAbsent(checkKey, "1", OBJECT_COUNTER_SAMPLE_CHECK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(shouldCheck)) {
            return raw;
        }
        long[] decoded = CountRedisCodec.decodeSlots(raw, schema.slotCount());
        for (ObjectCounterType metric : activeMetrics(metrics)) {
            int slot = schema.slotOf(metric);
            if (slot < 0 || slot >= decoded.length) {
                continue;
            }
            long truth = rebuildBitmapCount(metric, postId);
            if (Math.max(0L, decoded[slot]) != truth) {
                byte[] rebuilt = rebuildSnapshotIfPossible(postId, activeMetrics(metrics));
                return rebuilt.length == schema.totalPayloadBytes() ? rebuilt : raw;
            }
        }
        return raw;
    }

    private String acquireObjectCounterLock(String lockKey) {
        if (lockKey == null) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, REBUILD_LOCK_SECONDS, TimeUnit.SECONDS)) ? token : null;
    }

    private String acquireObjectCounterLockWithWait(String lockKey, int attempts, long sleepMs) {
        for (int i = 0; i < Math.max(1, attempts); i++) {
            String token = acquireObjectCounterLock(lockKey);
            if (token != null) {
                return token;
            }
            if (sleepMs > 0L && i + 1 < attempts) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private void releaseObjectCounterLock(String lockKey, String token) {
        if (lockKey == null || token == null) {
            return;
        }
        redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
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

    private boolean finalizeRebuildSnapshot(Long postId, ObjectCounterTarget target, CountRedisSchema schema,
                                            long[] slots, long watermark, List<ObjectCounterType> coveredMetrics,
                                            List<ObjectCounterType> writeMetrics) {
        String snapshotKey = CountRedisKeys.objectSnapshot(ReactionTargetTypeEnumVO.POST, postId);
        String aggregationKey = CountRedisKeys.objectAggregationBucket(ReactionTargetTypeEnumVO.POST, postId);
        String watermarkKey = CountRedisKeys.objectRebuildWatermark(target);
        if (snapshotKey == null || aggregationKey == null || watermarkKey == null || schema == null || slots == null) {
            return false;
        }
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(schema.slotCount()));
        args.add(watermarkKey);
        args.add(String.valueOf(Math.max(0L, watermark)));
        List<ObjectCounterType> activeCoveredMetrics = activeMetrics(coveredMetrics);
        args.add(String.valueOf(activeCoveredMetrics.size()));
        for (ObjectCounterType metric : activeCoveredMetrics) {
            int slot = schema.slotOf(metric);
            if (slot >= 0) {
                args.add(String.valueOf(slot));
            }
        }
        for (ObjectCounterType metric : activeMetrics(writeMetrics)) {
            int slot = schema.slotOf(metric);
            if (slot >= 0) {
                args.add(String.valueOf(slot));
                long value = slot < slots.length ? slots[slot] : 0L;
                args.add(String.valueOf(Math.max(0L, value)));
            }
        }
        Long result = redisTemplate.execute(
                FINALIZE_REBUILD_SCRIPT,
                List.of(snapshotKey, aggregationKey),
                args.toArray());
        return result != null && result == 1L;
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

    private long redisNowMs() {
        Long now = redisTemplate.execute((RedisCallback<Long>) connection -> {
            if (connection == null || connection.serverCommands() == null) {
                return null;
            }
            return connection.serverCommands().time(TimeUnit.MILLISECONDS);
        });
        return now == null || now <= 0L ? System.currentTimeMillis() : now;
    }
}
