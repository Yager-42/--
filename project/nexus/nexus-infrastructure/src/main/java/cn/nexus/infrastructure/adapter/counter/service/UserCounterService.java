package cn.nexus.infrastructure.adapter.counter.service;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.counter.model.valobj.UserRelationCounterVO;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisOperations;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

/**
 * User counter service based on Redis user snapshot plus mixed-source rebuild.
 */
@Service
@RequiredArgsConstructor
public class UserCounterService implements IUserCounterService {

    private static final long REBUILD_LOCK_SECONDS = 15L;
    private static final long REBUILD_RATE_LIMIT_SECONDS = 30L;
    private static final long USER_COUNTER_SAMPLE_CHECK_TTL_SECONDS = 300L;

    private final StringRedisTemplate redisTemplate;
    private final IRelationRepository relationRepository;
    private final IContentRepository contentRepository;
    private final IObjectCounterService objectCounterService;

    @Override
    public long getCount(Long userId, UserCounterType counterType) {
        if (userId == null || counterType == null) {
            return 0L;
        }
        return snapshotValue(userId, counterType);
    }

    @Override
    public long incrementFollowings(Long userId, long delta) {
        return increment(userId, UserCounterType.FOLLOWINGS, delta);
    }

    @Override
    public long incrementFollowers(Long userId, long delta) {
        return increment(userId, UserCounterType.FOLLOWERS, delta);
    }

    @Override
    public long incrementPosts(Long userId, long delta) {
        return increment(userId, UserCounterType.POSTS, delta);
    }

    @Override
    public long incrementLikesReceived(Long userId, long delta) {
        return increment(userId, UserCounterType.LIKES_RECEIVED, delta);
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

    @Override
    public void rebuildAllCounters(Long userId) {
        if (userId == null) {
            return;
        }
        rebuildSnapshot(userId, false);
    }

    @Override
    public UserRelationCounterVO readRelationCountersWithVerification(Long userId) {
        if (userId == null) {
            return zeros();
        }
        SnapshotState state = readSnapshotState(userId);
        if (!state.valid()) {
            rebuildAllCounters(userId);
            state = readSnapshotState(userId);
            if (!state.valid()) {
                return zeros();
            }
        }
        maybeVerifyRelationSlots(userId, state.snapshot());
        return toPublicCounters(state.snapshot());
    }

    private long increment(Long userId, UserCounterType counterType, long delta) {
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
        return operations.incrementSnapshotSlot(
                CountRedisKeys.userSnapshot(userId),
                CountRedisSchema.user().slotOf(counterType),
                delta,
                CountRedisSchema.user());
    }

    private long snapshotValue(Long userId, UserCounterType counterType) {
        if (userId == null || counterType == null) {
            return 0L;
        }
        String key = CountRedisKeys.userSnapshot(userId);
        if (key == null) {
            return 0L;
        }
        byte[] rawSnapshot = new CountRedisOperations(redisTemplate).readSnapshotPayload(key);
        if (rawSnapshot.length == 0) {
            return rebuildSnapshotValueIfNeeded(userId, counterType, key);
        }
        try {
            Map<String, Long> snapshot = decodeRawSnapshot(rawSnapshot);
            Long value = snapshot.get(counterType.getCode());
            if (value == null || snapshot.size() < CountRedisSchema.user().slotCount()
                    || rawSnapshot.length != CountRedisSchema.user().totalPayloadBytes()) {
                return rebuildSnapshotValueIfNeeded(userId, counterType, key);
            }
            return Math.max(0L, value);
        } catch (Exception ignored) {
            return rebuildSnapshotValueIfNeeded(userId, counterType, key);
        }
    }

    private long rebuildSnapshotValueIfNeeded(Long userId, UserCounterType counterType, String key) {
        Map<String, Long> rebuilt = rebuildSnapshot(userId, true);
        if (rebuilt == null || rebuilt.isEmpty()) {
            return 0L;
        }
        Long value = rebuilt.get(counterType.getCode());
        if (value != null) {
            return Math.max(0L, value);
        }
        byte[] rawSnapshot = new CountRedisOperations(redisTemplate).readSnapshotPayload(key);
        if (rawSnapshot.length == 0) {
            return 0L;
        }
        try {
            Map<String, Long> snapshot = decodeRawSnapshot(rawSnapshot);
            Long fallback = snapshot.get(counterType.getCode());
            return fallback == null ? 0L : Math.max(0L, fallback);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private Map<String, Long> rebuildSnapshot(Long userId, boolean guarded) {
        if (userId == null) {
            return Map.of();
        }
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        String rebuildLockKey = CountRedisKeys.userRebuildLock(userId);
        if (guarded) {
            String rateLimitKey = CountRedisKeys.userRebuildRateLimit(userId);
            if (rateLimitKey == null || !operations.tryAcquireRateLimit(rateLimitKey, REBUILD_RATE_LIMIT_SECONDS)) {
                return Map.of();
            }
            if (rebuildLockKey == null || !operations.tryAcquireRebuildLock(rebuildLockKey, REBUILD_LOCK_SECONDS)) {
                return Map.of();
            }
        }
        try {
            Map<String, Long> rebuilt = rebuildUserSnapshot(userId);
            operations.writeUserSnapshot(CountRedisKeys.userSnapshot(userId), rebuilt, CountRedisSchema.user());
            return rebuilt;
        } finally {
            if (guarded && rebuildLockKey != null) {
                operations.releaseRebuildLock(rebuildLockKey);
            }
        }
    }

    private Map<String, Long> rebuildUserSnapshot(Long userId) {
        return rebuildClass2Slots(userId, Map.of());
    }

    private Map<String, Long> rebuildClass2Slots(Long userId, Map<String, Long> previousSnapshot) {
        Map<String, Long> rebuilt = CountRedisSchema.userSnapshotDefaults();
        rebuilt.put(UserCounterType.FOLLOWINGS.getCode(),
                Math.max(0L, relationRepository.countActiveRelationsBySource(userId, 1)));
        rebuilt.put(UserCounterType.FOLLOWERS.getCode(),
                Math.max(0L, relationRepository.countFollowerIds(userId)));
        rebuilt.put(UserCounterType.POSTS.getCode(),
                Math.max(0L, contentRepository.countPublishedPostsByUser(userId)));
        long preservedLikeReceived = 0L;
        if (previousSnapshot != null) {
            Long prev = previousSnapshot.get(UserCounterType.LIKES_RECEIVED.getCode());
            if (prev != null) {
                preservedLikeReceived = Math.max(0L, prev);
            }
        }
        rebuilt.put(UserCounterType.LIKES_RECEIVED.getCode(), preservedLikeReceived);
        rebuilt.put(UserCounterType.FAVS_RECEIVED.getCode(), 0L);
        return rebuilt;
    }

    private long sumLikeReceivedBestEffort(Long userId) {
        if (userId == null) {
            return 0L;
        }
        List<ContentPostEntity> posts = contentRepository.listPublishedPostIdsByUser(userId);
        if (posts == null || posts.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (ContentPostEntity post : posts) {
            if (post == null || post.getPostId() == null) {
                continue;
            }
            try {
                Map<String, Long> values = objectCounterService.getCounts(
                        ReactionTargetTypeEnumVO.POST,
                        post.getPostId(),
                        List.of(ObjectCounterType.LIKE));
                Long like = values == null ? null : values.get(ObjectCounterType.LIKE.getCode());
                if (like != null && like > 0) {
                    sum += like;
                }
            } catch (Exception ignored) {
                // best-effort aggregation for class-1 display counter
            }
        }
        return Math.max(0L, sum);
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

    private Map<String, Long> decodeRawSnapshot(byte[] rawSnapshot) {
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

    private SnapshotState readSnapshotState(Long userId) {
        String key = CountRedisKeys.userSnapshot(userId);
        if (key == null) {
            return new SnapshotState(false, Map.of());
        }
        byte[] rawSnapshot = new CountRedisOperations(redisTemplate).readSnapshotPayload(key);
        if (rawSnapshot.length == 0) {
            return new SnapshotState(false, Map.of());
        }
        try {
            byte[] decoded = CountRedisCodec.fromRedisValue(rawSnapshot);
            if (decoded.length != CountRedisSchema.user().totalPayloadBytes()) {
                return new SnapshotState(false, Map.of());
            }
            Map<String, Long> snapshot = decodeRawSnapshot(rawSnapshot);
            if (snapshot.size() < CountRedisSchema.user().slotCount()) {
                return new SnapshotState(false, Map.of());
            }
            return new SnapshotState(true, snapshot);
        } catch (Exception ignored) {
            return new SnapshotState(false, Map.of());
        }
    }

    private void maybeVerifyRelationSlots(Long userId, Map<String, Long> snapshot) {
        if (userId == null || snapshot == null || snapshot.isEmpty()) {
            return;
        }
        String checkKey = CountRedisKeys.userCounterSampleCheck(userId);
        if (checkKey == null) {
            return;
        }
        Boolean shouldCheck = redisTemplate.opsForValue()
                .setIfAbsent(checkKey, "1", USER_COUNTER_SAMPLE_CHECK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(shouldCheck)) {
            return;
        }
        long following = valueOf(snapshot, UserCounterType.FOLLOWINGS);
        long follower = valueOf(snapshot, UserCounterType.FOLLOWERS);
        long truthFollowing = Math.max(0L, relationRepository.countActiveRelationsBySource(userId, 1));
        long truthFollower = Math.max(0L, relationRepository.countFollowerIds(userId));
        if (following != truthFollowing || follower != truthFollower) {
            rebuildAllCounters(userId);
            redisTemplate.opsForValue().setIfAbsent(
                    checkKey,
                    "1",
                    USER_COUNTER_SAMPLE_CHECK_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
        }
    }

    private UserRelationCounterVO toPublicCounters(Map<String, Long> snapshot) {
        return UserRelationCounterVO.builder()
                .followings(valueOf(snapshot, UserCounterType.FOLLOWINGS))
                .followers(valueOf(snapshot, UserCounterType.FOLLOWERS))
                .posts(valueOf(snapshot, UserCounterType.POSTS))
                .likedPosts(valueOf(snapshot, UserCounterType.LIKES_RECEIVED))
                .build();
    }

    private long valueOf(Map<String, Long> snapshot, UserCounterType type) {
        if (snapshot == null || type == null) {
            return 0L;
        }
        Long value = snapshot.get(type.getCode());
        return value == null ? 0L : Math.max(0L, value);
    }

    private UserRelationCounterVO zeros() {
        return UserRelationCounterVO.builder()
                .followings(0L)
                .followers(0L)
                .posts(0L)
                .likedPosts(0L)
                .build();
    }

    private record SnapshotState(boolean valid, Map<String, Long> snapshot) {
    }
}
