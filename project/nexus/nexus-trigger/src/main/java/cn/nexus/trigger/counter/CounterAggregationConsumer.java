package cn.nexus.trigger.counter;

import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import cn.nexus.domain.counter.model.event.CounterTopics;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CounterAggregationConsumer {

    private static final String POST = "post";
    private static final long REBUILD_LOCK_SECONDS = 300L;
    private static final int MESSAGE_LOCK_RETRY_ATTEMPTS = 50;
    private static final long MESSAGE_LOCK_RETRY_SLEEP_MS = 10L;
    private static final DefaultRedisScript<List> APPLY_AGG_DELTA_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('HGET', KEYS[1], ARGV[1]); "
                    + "if not v then return {'0','0'} end; "
                    + "local n = tonumber(v) or 0; "
                    + "if n == 0 then redis.call('HDEL', KEYS[1], ARGV[1]); return {'1','0'} end; "
                    + "local snapshot = KEYS[2]; "
                    + "local slot = tonumber(ARGV[1]); "
                    + "local slots = tonumber(ARGV[2]); "
                    + "local width = 4; "
                    + "local len = slots * width; "
                    + "local raw = redis.call('GET', snapshot); "
                    + "local initialized = '0'; "
                    + "if (not raw) or string.len(raw) ~= len then "
                    + "  raw = string.rep(string.char(0), len); "
                    + "  initialized = 'initialized'; "
                    + "end; "
                    + "local start = slot * width + 1; "
                    + "local b1, b2, b3, b4 = string.byte(raw, start, start + 3); "
                    + "local current = b1 * 16777216 + b2 * 65536 + b3 * 256 + b4; "
                    + "local next = current + n; "
                    + "if next < 0 then next = 0; end; "
                    + "if next > 4294967295 then next = 4294967295; end; "
                    + "local x = next; "
                    + "local nb4 = x % 256; x = math.floor(x / 256); "
                    + "local nb3 = x % 256; x = math.floor(x / 256); "
                    + "local nb2 = x % 256; x = math.floor(x / 256); "
                    + "local nb1 = x % 256; "
                    + "local updated = string.sub(raw, 1, start - 1) "
                    + ".. string.char(nb1, nb2, nb3, nb4) "
                    + ".. string.sub(raw, start + width); "
                    + "redis.call('SET', snapshot, updated); "
                    + "redis.call('HDEL', KEYS[1], ARGV[1]); "
                    + "return {'1', tostring(n), tostring(next), initialized}",
            List.class);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('DEL', KEYS[1]); "
                    + "else return 0; end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = CounterTopics.COUNTER_EVENTS,
            groupId = "counter-agg",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(String raw) {
        CounterDeltaEvent event = parse(raw);
        if (!isActivePostCounterEvent(event)) {
            return;
        }
        String lockKey = CountRedisKeys.objectRebuildLock(BucketMeta.target(event.getTargetId()));
        String lockToken = acquireObjectCounterLockWithWait(lockKey, MESSAGE_LOCK_RETRY_ATTEMPTS, MESSAGE_LOCK_RETRY_SLEEP_MS);
        if (lockToken == null) {
            throw new IllegalStateException("object counter lock unavailable, postId=" + event.getTargetId());
        }
        try {
            if (coveredByRebuildWatermark(event)) {
                return;
            }
            String bucket = CountRedisKeys.objectAggregationBucket(ReactionTargetTypeEnumVO.POST, event.getTargetId());
            redisTemplate.opsForHash().increment(bucket, String.valueOf(event.getSlot()), event.getDelta());
            redisTemplate.opsForSet().add(CountRedisKeys.objectAggregationActiveIndex(), bucket);
        } finally {
            releaseObjectCounterLock(lockKey, lockToken);
        }
    }

    @Scheduled(fixedDelay = 1000L)
    public void flushActiveBuckets() {
        String index = CountRedisKeys.objectAggregationActiveIndex();
        Long size = redisTemplate.opsForSet().size(index);
        long limit = size == null ? 0L : Math.min(size, 4096L);
        for (long i = 0; i < limit; i++) {
            String bucket = redisTemplate.opsForSet().pop(index);
            if (bucket == null || bucket.isBlank()) {
                return;
            }
            if (flushBucket(bucket)) {
                redisTemplate.opsForSet().add(index, bucket);
            }
        }
    }

    private boolean flushBucket(String bucket) {
        BucketMeta meta = BucketMeta.parse(bucket);
        if (meta == null) {
            return false;
        }
        String lockKey = CountRedisKeys.objectRebuildLock(meta.toTarget());
        String lockToken = acquireObjectCounterLock(lockKey);
        if (lockToken == null) {
            return true;
        }
        try {
            return flushBucketLocked(bucket, meta);
        } finally {
            releaseObjectCounterLock(lockKey, lockToken);
        }
    }

    private boolean flushBucketLocked(String bucket, BucketMeta meta) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(bucket);
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        boolean retry = false;
        for (Object rawField : entries.keySet()) {
            String field = String.valueOf(rawField);
            ApplyResult result = applyAggregationDelta(bucket, meta.targetId(), field);
            if (!result.applied()) {
                retry = true;
            }
        }
        Long remaining = redisTemplate.opsForHash().size(bucket);
        return retry || remaining == null || remaining > 0L;
    }

    private ApplyResult applyAggregationDelta(String bucket, Long postId, String field) {
        CountRedisSchema schema = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST);
        int slot = parseSlot(field);
        if (schema == null || slot != 1 && slot != 2) {
            redisTemplate.opsForHash().delete(bucket, field);
            return new ApplyResult(true, 0L);
        }
        List<?> result = redisTemplate.execute(
                APPLY_AGG_DELTA_SCRIPT,
                List.of(bucket, CountRedisKeys.objectSnapshot(ReactionTargetTypeEnumVO.POST, postId)),
                field,
                String.valueOf(schema.slotCount()));
        if (result == null || result.size() < 2 || !"1".equals(String.valueOf(result.get(0)))) {
            return new ApplyResult(false, 0L);
        }
        long updated = result.size() < 3 ? 0L : parseLong(result.get(2));
        return new ApplyResult(true, updated);
    }

    private CounterDeltaEvent parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw.getBytes(StandardCharsets.UTF_8), CounterDeltaEvent.class);
        } catch (Exception e) {
            log.warn("parse counter delta event failed", e);
            return null;
        }
    }

    private boolean isActivePostCounterEvent(CounterDeltaEvent event) {
        if (event == null || event.getTargetId() == null || event.getMetric() == null
                || event.getSlot() == null || event.getDelta() == null || event.getDelta() == 0L
                || event.getTsMs() == null) {
            return false;
        }
        if (!POST.equals(event.getTargetType())) {
            return false;
        }
        if (ObjectCounterType.LIKE.getCode().equals(event.getMetric())) {
            return event.getSlot() == 1;
        }
        if (ObjectCounterType.FAV.getCode().equals(event.getMetric())) {
            return event.getSlot() == 2;
        }
        return false;
    }

    private boolean coveredByRebuildWatermark(CounterDeltaEvent event) {
        if (event == null || event.getTargetId() == null || event.getTsMs() == null) {
            return false;
        }
        Long watermark = parseLongOrNull(redisTemplate.opsForValue()
                .get(CountRedisKeys.objectRebuildWatermark(BucketMeta.target(event.getTargetId()), event.getSlot())));
        return watermark != null && event.getTsMs() <= watermark;
    }

    private int parseSlot(String field) {
        try {
            return Integer.parseInt(field);
        } catch (NumberFormatException ignored) {
            return -1;
        }
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

    private Long parseLongOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private record BucketMeta(Long targetId) {

        private cn.nexus.domain.counter.model.valobj.ObjectCounterTarget toTarget() {
            return target(targetId);
        }

        private static cn.nexus.domain.counter.model.valobj.ObjectCounterTarget target(Long targetId) {
            return cn.nexus.domain.counter.model.valobj.ObjectCounterTarget.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(targetId)
                    .build();
        }

        private static BucketMeta parse(String bucket) {
            if (bucket == null || bucket.isBlank()) {
                return null;
            }
            String[] parts = bucket.split(":");
            if (parts.length != 4 || !"agg".equals(parts[0])
                    || !CountRedisSchema.SCHEMA_ID.equals(parts[1])
                    || !POST.equals(parts[2])) {
                return null;
            }
            Long id = parseId(parts[3]);
            return id == null ? null : new BucketMeta(id);
        }

        private static Long parseId(String raw) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private record ApplyResult(boolean applied, long updated) {
    }
}
