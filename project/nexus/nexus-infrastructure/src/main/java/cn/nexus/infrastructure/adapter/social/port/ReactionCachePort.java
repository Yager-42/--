package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import cn.nexus.infrastructure.config.HotKeyStoreBridge;
import cn.nexus.infrastructure.support.SingleFlight;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReactionCachePort implements IReactionCachePort {

    private static final long BIT_SHARD_SIZE = 1_000_000L;
    private static final String KEY_RECOVERY_CP_PREFIX = "count:replay:checkpoint:";
    private static final String HOTKEY_PREFIX = "like__";
    private static final int L1_MAX_SIZE = 100_000;
    private static final Duration L1_TTL = Duration.ofSeconds(2);

    private final StringRedisTemplate stringRedisTemplate;
    private final HotKeyStoreBridge hotKeyStoreBridge;

    private final Cache<String, Long> countCache = Caffeine.newBuilder()
            .maximumSize(L1_MAX_SIZE)
            .expireAfterWrite(L1_TTL)
            .build();

    private final SingleFlight singleFlight = new SingleFlight();

    @Override
    public ReactionApplyResultVO applyAtomic(Long userId, ReactionTargetVO target, int desiredState) {
        if (!supportsLikeFact(target) || userId == null || userId < 0 || (desiredState != 0 && desiredState != 1)) {
            return null;
        }

        String bitmapKey = bitmapKey(target, userId);
        long offset = offset(userId);
        boolean nextState = desiredState == 1;
        // Use Redis SETBIT returned previous state as the atomic source of truth for effective delta.
        Boolean previousState = stringRedisTemplate.opsForValue().setBit(bitmapKey, offset, nextState);
        boolean currentState = Boolean.TRUE.equals(previousState);
        int delta = currentState == nextState ? 0 : (nextState ? 1 : -1);
        if (delta != 0) {
            countCache.invalidate(hotkeyKey(target));
        }

        long currentCount = getCountFromRedis(target);
        return ReactionApplyResultVO.builder()
                .currentCount(currentCount)
                .delta(delta)
                .firstPending(false)
                .build();
    }

    @Override
    public long getCount(ReactionTargetVO target) {
        if (!supportsLikeFact(target)) {
            return 0L;
        }
        String hotkey = hotkeyKey(target);
        boolean hot = isHotKeySafe(hotkey);
        if (hot) {
            Long cached = countCache.getIfPresent(hotkey);
            if (cached != null) {
                return cached;
            }
        }
        long count = singleFlight.execute(target.hashTag(), () -> countBitmapFacts(target));
        if (hot) {
            countCache.put(hotkey, count);
        }
        return count;
    }

    @Override
    public Map<String, Long> batchGetCount(List<ReactionTargetVO> targets) {
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> result = new HashMap<>(targets.size());
        for (ReactionTargetVO target : targets) {
            if (target == null) {
                continue;
            }
            result.put(target.hashTag(), getCountFromRedis(target));
        }
        return result;
    }

    @Override
    public long getCountFromRedis(ReactionTargetVO target) {
        return supportsLikeFact(target) ? countBitmapFacts(target) : 0L;
    }

    @Override
    public boolean getState(Long userId, ReactionTargetVO target) {
        if (!supportsLikeFact(target) || userId == null || userId < 0) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().getBit(bitmapKey(target, userId), offset(userId)));
    }

    @Override
    public boolean applyRecoveryEvent(Long userId, ReactionTargetVO target, int desiredState) {
        if (!supportsLikeFact(target) || userId == null || userId < 0 || (desiredState != 0 && desiredState != 1)) {
            return false;
        }
        stringRedisTemplate.opsForValue().setBit(bitmapKey(target, userId), offset(userId), desiredState == 1);
        countCache.invalidate(hotkeyKey(target));
        return true;
    }

    @Override
    public Long getRecoveryCheckpoint(String targetType, String reactionType) {
        return parseLong(stringRedisTemplate.opsForValue().get(recoveryCheckpointKey(targetType, reactionType)));
    }

    @Override
    public void setRecoveryCheckpoint(String targetType, String reactionType, Long seq) {
        if (seq == null || seq < 0) {
            return;
        }
        stringRedisTemplate.opsForValue().set(recoveryCheckpointKey(targetType, reactionType), String.valueOf(seq));
    }

    @Override
    public long getWindowMs(ReactionTargetVO target, long defaultMs) {
        return defaultMs;
    }

    private boolean supportsLikeFact(ReactionTargetVO target) {
        return target != null
                && target.getTargetId() != null
                && target.getReactionType() == ReactionTypeEnumVO.LIKE
                && (target.getTargetType() == ReactionTargetTypeEnumVO.POST
                || target.getTargetType() == ReactionTargetTypeEnumVO.COMMENT);
    }

    private String bitmapKey(ReactionTargetVO target, Long userId) {
        long shard = userId == null ? 0L : userId / BIT_SHARD_SIZE;
        return CountRedisKeys.likeBitmapShard(target.getTargetType(), target.getTargetId(), shard);
    }

    private long offset(Long userId) {
        return userId % BIT_SHARD_SIZE;
    }

    private long countBitmapFacts(ReactionTargetVO target) {
        ObjectCounterTarget counterTarget = ObjectCounterTarget.builder()
                .targetType(target.getTargetType())
                .targetId(target.getTargetId())
                .counterType(ObjectCounterType.LIKE)
                .build();
        String pattern = CountRedisKeys.likeBitmapShardPattern(counterTarget);
        if (pattern == null) {
            return 0L;
        }
        Set<String> shardKeys = stringRedisTemplate.keys(pattern);
        if (shardKeys == null || shardKeys.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (String shardKey : shardKeys) {
            if (shardKey == null || shardKey.isBlank()) {
                continue;
            }
            Long shardCount = stringRedisTemplate.execute((RedisCallback<Long>) connection -> bitCount(connection, shardKey));
            total += shardCount == null ? 0L : Math.max(0L, shardCount);
        }
        return Math.max(0L, total);
    }

    private Long bitCount(RedisConnection connection, String shardKey) {
        if (connection == null || shardKey == null || shardKey.isBlank()) {
            return 0L;
        }
        byte[] key = stringRedisTemplate.getStringSerializer().serialize(shardKey);
        if (key == null) {
            return 0L;
        }
        return connection.stringCommands().bitCount(key);
    }

    private String recoveryCheckpointKey(String targetType, String reactionType) {
        return KEY_RECOVERY_CP_PREFIX + (targetType == null ? "" : targetType) + ":" + (reactionType == null ? "" : reactionType);
    }

    private String hotkeyKey(ReactionTargetVO target) {
        return HOTKEY_PREFIX + (target == null ? "" : target.hashTag());
    }

    private boolean isHotKeySafe(String hotkey) {
        try {
            return hotKeyStoreBridge.isHotKey(hotkey);
        } catch (Exception e) {
            log.warn("jd-hotkey isHotKey failed, hotkey={}", hotkey, e);
            return false;
        }
    }

    private Long parseLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
