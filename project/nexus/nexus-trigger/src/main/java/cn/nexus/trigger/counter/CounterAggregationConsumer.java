package cn.nexus.trigger.counter;

import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import cn.nexus.domain.counter.model.event.CounterTopics;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisOperations;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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

    private static final int AGG_SHARDS = 64;
    private static final DefaultRedisScript<List> DRAIN_FIELD_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('HGET', KEYS[1], ARGV[1]); "
                    + "if not v then return {'0','0'} end; "
                    + "local n = tonumber(v) or 0; "
                    + "if n == 0 then redis.call('HDEL', KEYS[1], ARGV[1]); return {'1','0'} end; "
                    + "redis.call('HINCRBY', KEYS[1], ARGV[1], -n); "
                    + "return {'1', tostring(n)}",
            List.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = CounterTopics.COUNTER_EVENTS,
            groupId = "counter-agg",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(String raw) {
        CounterDeltaEvent event = parse(raw);
        if (!isActiveObjectLikeEvent(event)) {
            return;
        }
        String bucket = CountRedisKeys.objectAggregationBucket(
                event.getEntityType(),
                event.getEntityId(),
                aggregationShard(event));
        redisTemplate.opsForHash().increment(bucket, String.valueOf(event.getIdx()), event.getDelta());
        redisTemplate.opsForSet().add(CountRedisKeys.objectAggregationActiveIndex(), bucket);
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
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(bucket);
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        boolean hasRemainder = false;
        for (Object rawField : entries.keySet()) {
            String field = String.valueOf(rawField);
            long delta = drainField(bucket, field);
            if (delta == 0L) {
                hasRemainder = true;
                continue;
            }
            applySnapshotDelta(meta.targetType(), meta.targetId(), field, delta);
        }
        Long remaining = redisTemplate.opsForHash().size(bucket);
        return hasRemainder || remaining == null || remaining > 0L;
    }

    private long drainField(String bucket, String field) {
        List<?> result = redisTemplate.execute(DRAIN_FIELD_SCRIPT, List.of(bucket), field);
        if (result == null || result.size() < 2) {
            return 0L;
        }
        Object drained = result.get(0);
        if (!"1".equals(String.valueOf(drained))) {
            return 0L;
        }
        return parseLong(result.get(1));
    }

    private void applySnapshotDelta(ReactionTargetTypeEnumVO targetType, Long targetId, String field, long delta) {
        CountRedisSchema schema = CountRedisSchema.forObject(targetType);
        int slot = parseSlot(field);
        if (schema == null || slot < 0 || slot >= schema.slotCount()) {
            return;
        }
        new CountRedisOperations(redisTemplate)
                .incrementSnapshotSlot(CountRedisKeys.objectSnapshot(targetType, targetId), slot, delta, schema);
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

    private boolean isActiveObjectLikeEvent(CounterDeltaEvent event) {
        return event != null
                && event.getEntityType() != null
                && event.getEntityId() != null
                && event.getMetric() == ObjectCounterType.LIKE
                && event.getIdx() != null
                && event.getDelta() != null
                && (event.getEntityType() == ReactionTargetTypeEnumVO.POST
                || event.getEntityType() == ReactionTargetTypeEnumVO.COMMENT);
    }

    private long aggregationShard(CounterDeltaEvent event) {
        long user = event.getUserId() == null ? 0L : event.getUserId();
        return Math.floorMod(user, AGG_SHARDS);
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

    private record BucketMeta(ReactionTargetTypeEnumVO targetType, Long targetId) {

        private static BucketMeta parse(String bucket) {
            if (bucket == null || bucket.isBlank()) {
                return null;
            }
            String[] parts = bucket.split(":");
            if (parts.length != 5 || !"agg".equals(parts[0]) || !CountRedisSchema.SCHEMA_ID.equals(parts[1])) {
                return null;
            }
            ReactionTargetTypeEnumVO type = ReactionTargetTypeEnumVO.from(parts[2]);
            Long id = parseId(parts[3]);
            if (type == null || id == null) {
                return null;
            }
            return new BucketMeta(type, id);
        }

        private static Long parseId(String raw) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
