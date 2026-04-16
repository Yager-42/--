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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

    private static final DefaultRedisScript<List> REACTION_APPLY_SCRIPT;

    static {
        REACTION_APPLY_SCRIPT = new DefaultRedisScript<>();
        REACTION_APPLY_SCRIPT.setResultType(List.class);
        REACTION_APPLY_SCRIPT.setScriptText(
                "local prev = redis.call('SETBIT', KEYS[1], ARGV[1], ARGV[2])\n"
                        + "local desired = tonumber(ARGV[2])\n"
                        + "if prev == desired then\n"
                        + "  local raw = redis.call('GET', KEYS[2])\n"
                        + "  local cnt = raw and tonumber(raw) or 0\n"
                        + "  if (not cnt) or cnt < 0 then cnt = 0 end\n"
                        + "  return {cnt, 0}\n"
                        + "end\n"
                        + "local delta = desired == 1 and 1 or -1\n"
                        + "local cnt = redis.call('INCRBY', KEYS[2], delta)\n"
                        + "if cnt < 0 then\n"
                        + "  redis.call('SET', KEYS[2], 0)\n"
                        + "  cnt = 0\n"
                        + "end\n"
                        + "return {cnt, delta}\n");
    }

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

        ApplyEvalResult eval = executeApplyScript(bitmapKey(target, userId), countKey(target), offset(userId), desiredState);
        if (eval.delta() != 0) {
            countCache.invalidate(hotkeyKey(target));
        }

        return ReactionApplyResultVO.builder()
                .currentCount(eval.currentCount())
                .delta(eval.delta())
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
        long count = getCountFromRedis(target);
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
        List<ReactionTargetVO> supportedTargets = new ArrayList<>(targets.size());
        List<String> countKeys = new ArrayList<>(targets.size());

        for (ReactionTargetVO target : targets) {
            if (target == null) {
                continue;
            }
            if (!supportsLikeFact(target)) {
                result.put(target.hashTag(), 0L);
                continue;
            }
            supportedTargets.add(target);
            countKeys.add(countKey(target));
        }

        List<String> countValues = supportedTargets.isEmpty() ? List.of() : stringRedisTemplate.opsForValue().multiGet(countKeys);
        for (int i = 0; i < supportedTargets.size(); i++) {
            ReactionTargetVO target = supportedTargets.get(i);
            Long parsed = null;
            if (countValues != null && i < countValues.size()) {
                parsed = parseLong(countValues.get(i));
            }
            long count = parsed == null ? getCountFromRedis(target) : Math.max(0L, parsed);
            result.put(target.hashTag(), count);
        }
        return result;
    }

    @Override
    public long getCountFromRedis(ReactionTargetVO target) {
        if (!supportsLikeFact(target)) {
            return 0L;
        }
        String countKey = countKey(target);
        Long current = parseLong(stringRedisTemplate.opsForValue().get(countKey));
        if (current != null) {
            return Math.max(0L, current);
        }
        return singleFlight.execute(target.hashTag(), () -> rebuildCountFromBitmapFacts(target, countKey));
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
        ApplyEvalResult eval = executeApplyScript(bitmapKey(target, userId), countKey(target), offset(userId), desiredState);
        if (eval.delta() != 0) {
            countCache.invalidate(hotkeyKey(target));
        }
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

    private String countKey(ReactionTargetVO target) {
        return CountRedisKeys.likeFactCount(target.getTargetType(), target.getTargetId());
    }

    private long offset(Long userId) {
        return userId % BIT_SHARD_SIZE;
    }

    private long rebuildCountFromBitmapFacts(ReactionTargetVO target, String countKey) {
        Long current = parseLong(stringRedisTemplate.opsForValue().get(countKey));
        if (current != null) {
            return Math.max(0L, current);
        }

        long rebuilt = countBitmapFactsByScan(target);
        stringRedisTemplate.opsForValue().set(countKey, String.valueOf(rebuilt));
        return rebuilt;
    }

    private long countBitmapFactsByScan(ReactionTargetVO target) {
        ObjectCounterTarget counterTarget = ObjectCounterTarget.builder()
                .targetType(target.getTargetType())
                .targetId(target.getTargetId())
                .counterType(ObjectCounterType.LIKE)
                .build();
        String pattern = CountRedisKeys.likeBitmapShardPattern(counterTarget);
        if (pattern == null) {
            return 0L;
        }

        Long total = stringRedisTemplate.execute((RedisCallback<Long>) connection -> {
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
                throw new IllegalStateException("scan bitmap facts failed, pattern=" + pattern, e);
            }
            return Math.max(0L, sum);
        });

        return total == null ? 0L : Math.max(0L, total);
    }

    private ApplyEvalResult executeApplyScript(String bitmapKey, String countKey, long offset, int desiredState) {
        List<?> raw = stringRedisTemplate.execute(
                REACTION_APPLY_SCRIPT,
                List.of(bitmapKey, countKey),
                String.valueOf(offset),
                String.valueOf(desiredState)
        );

        if (raw == null || raw.size() < 2) {
            Long current = parseLong(stringRedisTemplate.opsForValue().get(countKey));
            long fallbackCount = current == null ? 0L : Math.max(0L, current);
            return new ApplyEvalResult(fallbackCount, 0);
        }

        long currentCount = parseScriptLong(raw.get(0), 0L);
        int delta = (int) parseScriptLong(raw.get(1), 0L);
        return new ApplyEvalResult(Math.max(0L, currentCount), delta);
    }

    private long parseScriptLong(Object raw, long defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (Exception e) {
            return defaultValue;
        }
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

    private record ApplyEvalResult(long currentCount, int delta) {
    }
}
