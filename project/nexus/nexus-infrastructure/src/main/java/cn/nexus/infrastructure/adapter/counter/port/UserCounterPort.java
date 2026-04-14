package cn.nexus.infrastructure.adapter.counter.port;

import cn.nexus.domain.counter.adapter.port.IUserCounterPort;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisOperations;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisSchema;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 用户维度统一计数 Redis 实现。
 *
 * @author codex
 * @since 2026-04-02
 */
@Component
@RequiredArgsConstructor
public class UserCounterPort implements IUserCounterPort {

    private static final long REBUILD_LOCK_SECONDS = 15L;
    private static final long REBUILD_RATE_LIMIT_SECONDS = 30L;

    private final StringRedisTemplate redisTemplate;
    private final IRelationRepository relationRepository;

    @Override
    public long getCount(Long userId, UserCounterType counterType) {
        if (userId == null || counterType == null) {
            return 0L;
        }
        return snapshotValue(userId, counterType);
    }

    @Override
    public long increment(Long userId, UserCounterType counterType, long delta) {
        if (userId == null || counterType == null) {
            return 0L;
        }
        if (delta == 0) {
            return getCount(userId, counterType);
        }
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        String aggregationBucket = CountRedisKeys.userAggregationBucket(counterType);
        if (aggregationBucket != null) {
            operations.addAggregationDelta(aggregationBucket, String.valueOf(userId), delta);
        }
        long updated = Math.max(0L, snapshotValue(userId, counterType) + delta);
        writeSnapshotValue(userId, counterType, updated);
        return updated;
    }

    @Override
    public void setCount(Long userId, UserCounterType counterType, long count) {
        if (userId == null || counterType == null) {
            return;
        }
        writeSnapshotValue(userId, counterType, Math.max(0L, count));
    }

    @Override
    public void evict(Long userId, UserCounterType counterType) {
        if (userId == null || counterType == null) {
            return;
        }
        redisTemplate.delete(CountRedisKeys.userSnapshot(userId));
    }

    private long snapshotValue(Long userId, UserCounterType counterType) {
        if (userId == null || counterType == null) {
            return 0L;
        }
        String key = CountRedisKeys.userSnapshot(userId);
        if (key == null) {
            return 0L;
        }
        String rawSnapshot = redisTemplate.opsForValue().get(key);
        if (rawSnapshot == null || rawSnapshot.isBlank()) {
            return rebuildSnapshotValueIfSupported(userId, counterType, key);
        }
        try {
            Map<String, Long> snapshot = decodeRawSnapshot(rawSnapshot);
            Long value = snapshot.get(counterType.getCode());
            if (needsRebuild(counterType, value, snapshot, rawSnapshot)) {
                return rebuildSnapshotValueIfSupported(userId, counterType, key);
            }
            return value == null ? 0L : Math.max(0L, value);
        } catch (Exception ignored) {
            return rebuildSnapshotValueIfSupported(userId, counterType, key);
        }
    }

    private void writeSnapshotValue(Long userId, UserCounterType counterType, long count) {
        if (userId == null || counterType == null) {
            return;
        }
        String key = CountRedisKeys.userSnapshot(userId);
        if (key == null) {
            return;
        }
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        Map<String, Long> snapshot = operations.readUserSnapshot(key, CountRedisSchema.user());
        snapshot.put(counterType.getCode(), Math.max(0L, count));
        operations.writeUserSnapshot(key, snapshot, CountRedisSchema.user());
    }

    private boolean needsRebuild(UserCounterType counterType, Long value, Map<String, Long> snapshot, String rawSnapshot) {
        return supportsRelationRebuild(counterType)
                && (value == null
                || snapshot == null
                || snapshot.size() < CountRedisSchema.user().slotCount()
                || CountRedisCodec.fromRedisValue(rawSnapshot).length != CountRedisSchema.user().totalPayloadBytes());
    }

    private boolean supportsRelationRebuild(UserCounterType counterType) {
        return counterType == UserCounterType.FOLLOWING || counterType == UserCounterType.FOLLOWER;
    }

    private Map<String, Long> rebuildUserSnapshot(Long userId) {
        Map<String, Long> rebuilt = CountRedisSchema.userSnapshotDefaults();
        rebuilt.put(UserCounterType.FOLLOWING.getCode(),
                Math.max(0L, relationRepository.countActiveRelationsBySource(userId, 1)));
        rebuilt.put(UserCounterType.FOLLOWER.getCode(),
                Math.max(0L, relationRepository.countFollowerIds(userId)));
        return rebuilt;
    }

    private long rebuildSnapshotValueIfSupported(Long userId, UserCounterType counterType, String key) {
        if (!supportsRelationRebuild(counterType)) {
            return 0L;
        }
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        String rateLimitKey = CountRedisKeys.userRebuildRateLimit(userId);
        if (rateLimitKey == null || !operations.tryAcquireRateLimit(rateLimitKey, REBUILD_RATE_LIMIT_SECONDS)) {
            return 0L;
        }
        String rebuildLockKey = CountRedisKeys.userRebuildLock(userId);
        if (rebuildLockKey == null || !operations.tryAcquireRebuildLock(rebuildLockKey, REBUILD_LOCK_SECONDS)) {
            return 0L;
        }
        try {
            Map<String, Long> rebuilt = rebuildUserSnapshot(userId);
            operations.writeUserSnapshot(key, rebuilt, CountRedisSchema.user());
            Long rebuiltValue = rebuilt.get(counterType.getCode());
            return rebuiltValue == null ? 0L : Math.max(0L, rebuiltValue);
        } finally {
            operations.releaseRebuildLock(rebuildLockKey);
        }
    }

    private Map<String, Long> decodeRawSnapshot(String rawSnapshot) {
        CountRedisSchema schema = CountRedisSchema.user();
        long[] decoded = CountRedisCodec.decodeSlots(
                CountRedisCodec.fromRedisValue(rawSnapshot),
                schema.slotCount());
        Map<String, Long> snapshot = new HashMap<>(schema.slotCount());
        String[] fieldNames = schema.orderedFieldNames();
        for (int i = 0; i < fieldNames.length; i++) {
            snapshot.put(fieldNames[i], decoded[i]);
        }
        return snapshot;
    }
}
