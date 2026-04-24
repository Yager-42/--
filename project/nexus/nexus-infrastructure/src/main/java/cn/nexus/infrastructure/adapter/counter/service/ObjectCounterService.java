package cn.nexus.infrastructure.adapter.counter.service;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed object counter service with bitmap truth and snapshot reads.
 */
@Service
@RequiredArgsConstructor
public class ObjectCounterService implements IObjectCounterService {

    private final StringRedisTemplate redisTemplate;

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
        byte[] raw = safeDecode(redisTemplate.opsForValue().get(CountRedisKeys.objectSnapshot(targetType, targetId)));
        boolean malformed = raw.length != CountRedisSchema.forObject(targetType).totalPayloadBytes();
        for (ObjectCounterType metric : metrics) {
            if (metric == null || metric != ObjectCounterType.LIKE) {
                continue;
            }
            if (malformed) {
                values.put(metric.getCode(), 0L);
                continue;
            }
            int slot = CountRedisSchema.forObject(targetType).slotOf(metric);
            long[] decoded = CountRedisCodec.decodeSlots(raw, CountRedisSchema.forObject(targetType).slotCount());
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
        List<String> raws = redisTemplate.opsForValue().multiGet(keys);

        for (int i = 0; i < targetIds.size(); i++) {
            Long targetId = targetIds.get(i);
            byte[] raw = safeDecode(raws == null || i >= raws.size() ? null : raws.get(i));
            Map<String, Long> values = new LinkedHashMap<>();
            boolean malformed = raw.length != CountRedisSchema.forObject(targetType).totalPayloadBytes();
            for (ObjectCounterType metric : metrics) {
                if (metric == null || metric != ObjectCounterType.LIKE) {
                    continue;
                }
                if (malformed) {
                    values.put(metric.getCode(), 0L);
                    continue;
                }
                int slot = CountRedisSchema.forObject(targetType).slotOf(metric);
                long[] decoded = CountRedisCodec.decodeSlots(raw, CountRedisSchema.forObject(targetType).slotCount());
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
        String aggKey = CountRedisKeys.objectAggregationBucket(targetType, targetId);
        int slot = CountRedisSchema.forObject(targetType).slotOf(ObjectCounterType.LIKE);
        redisTemplate.opsForHash().increment(aggKey, String.valueOf(slot), desiredState ? 1L : -1L);
        return true;
    }

    private boolean isValidLikeTarget(ReactionTargetTypeEnumVO targetType, Long targetId, Long userId) {
        return targetType != null
                && targetId != null
                && userId != null
                && (targetType == ReactionTargetTypeEnumVO.POST || targetType == ReactionTargetTypeEnumVO.COMMENT);
    }

    private byte[] safeDecode(String raw) {
        try {
            return CountRedisCodec.fromRedisValue(raw);
        } catch (Exception ignored) {
            return new byte[0];
        }
    }
}
