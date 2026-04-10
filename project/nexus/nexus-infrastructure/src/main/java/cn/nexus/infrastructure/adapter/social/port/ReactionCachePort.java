package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.infrastructure.config.HotKeyStoreBridge;
import cn.nexus.infrastructure.support.SingleFlight;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 点赞缓存端口 Redis 实现。
 *
 * Redis 是在线真相源；MySQL 不再参与计数读取或写路径确认。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactionCachePort implements IReactionCachePort {

    private static final String KEY_BM_PREFIX = "interact:reaction:bm:";
    private static final long BIT_SHARD_SIZE = 1_000_000L;
    private static final String KEY_CNT_PREFIX = "interact:reaction:cnt:";
    private static final String KEY_RECOVERY_CP_PREFIX = "interact:reaction:recovery:cp:";
    private static final String KEY_WINDOW_MS_PREFIX = "interact:reaction:window_ms:";

    private static final long WINDOW_MS_MIN = 1_000L;
    private static final long WINDOW_MS_MAX = 600_000L;

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
    public ReactionApplyResultVO applyAtomic(Long userId,
                                             ReactionTargetVO target,
                                             int desiredState) {
        if (userId == null || target == null || userId < 0) {
            return null;
        }

        long shard = userId / BIT_SHARD_SIZE;
        long offset = userId % BIT_SHARD_SIZE;
        List<String> keys = List.of(
                bmKey(target, shard),
                cntKey(target)
        );
        List<String> argv = List.of(
                String.valueOf(desiredState),
                String.valueOf(offset)
        );

        List<?> res = stringRedisTemplate.execute(APPLY_ATOMIC_SCRIPT, keys, argv.toArray());
        long currentCount = listLong(res, 0, -1L);
        if (currentCount < 0) {
            return null;
        }

        int delta = (int) listLong(res, 1, 0L);
        countCache.invalidate(hotkeyKey(target));
        return ReactionApplyResultVO.builder()
                .currentCount(currentCount)
                .delta(delta)
                .firstPending(false)
                .build();
    }

    @Override
    public long getCount(ReactionTargetVO target) {
        if (target == null) {
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

        long cnt = singleFlight.execute(target.hashTag(), () -> redisCountOnly(target));
        if (hot) {
            countCache.put(hotkey, cnt);
        }
        return cnt;
    }

    @Override
    public Map<String, Long> batchGetCount(List<ReactionTargetVO> targets) {
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }

        List<String> keys = new ArrayList<>(targets.size());
        for (ReactionTargetVO target : targets) {
            keys.add(target == null ? null : cntKey(target));
        }

        List<String> values;
        try {
            values = stringRedisTemplate.opsForValue().multiGet(keys);
        } catch (Exception e) {
            log.warn("batchGetCount redis multiGet failed", e);
            values = null;
        }

        java.util.HashMap<String, Long> result = new java.util.HashMap<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            ReactionTargetVO target = targets.get(i);
            if (target == null) {
                continue;
            }
            String raw = values != null && i < values.size() ? values.get(i) : null;
            Long count = parseLong(raw);
            result.put(target.hashTag(), count == null || count < 0 ? 0L : count);
        }
        return result;
    }

    @Override
    public long getCountFromRedis(ReactionTargetVO target) {
        if (target == null) {
            return 0L;
        }
        return redisCountOnly(target);
    }

    @Override
    public boolean getState(Long userId, ReactionTargetVO target) {
        if (userId == null || target == null || userId < 0) {
            return false;
        }
        long shard = userId / BIT_SHARD_SIZE;
        long offset = userId % BIT_SHARD_SIZE;
        Boolean bit = stringRedisTemplate.opsForValue().getBit(bmKey(target, shard), offset);
        return Boolean.TRUE.equals(bit);
    }

    @Override
    public boolean bitmapShardExists(Long userId, ReactionTargetVO target) {
        if (userId == null || target == null || userId < 0) {
            return false;
        }
        long shard = userId / BIT_SHARD_SIZE;
        Boolean exists = stringRedisTemplate.hasKey(bmKey(target, shard));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void setState(Long userId, ReactionTargetVO target, boolean state) {
        if (userId == null || target == null || userId < 0) {
            return;
        }
        long shard = userId / BIT_SHARD_SIZE;
        long offset = userId % BIT_SHARD_SIZE;
        stringRedisTemplate.opsForValue().setBit(bmKey(target, shard), offset, state);
    }

    @Override
    public void setCount(ReactionTargetVO target, long count) {
        if (target == null) {
            return;
        }
        long safe = Math.max(0L, count);
        stringRedisTemplate.opsForValue().set(cntKey(target), String.valueOf(safe));
        countCache.invalidate(hotkeyKey(target));
    }

    @Override
    public boolean applyRecoveryEvent(Long userId,
                                      ReactionTargetVO target,
                                      int desiredState) {
        if (userId == null || target == null || userId < 0) {
            return false;
        }

        long shard = userId / BIT_SHARD_SIZE;
        long offset = userId % BIT_SHARD_SIZE;
        List<String> keys = List.of(
                bmKey(target, shard),
                cntKey(target)
        );
        List<String> argv = List.of(
                String.valueOf(desiredState),
                String.valueOf(offset)
        );

        List<?> res = stringRedisTemplate.execute(APPLY_RECOVERY_SCRIPT, keys, argv.toArray());
        long currentCount = listLong(res, 0, -1L);
        if (currentCount < 0) {
            return false;
        }
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
        if (target == null) {
            return defaultMs;
        }
        String raw = stringRedisTemplate.opsForValue().get(windowMsKey(target));
        Long ms = parseLong(raw);
        if (ms == null) {
            return defaultMs;
        }
        return clamp(ms, WINDOW_MS_MIN, WINDOW_MS_MAX);
    }

    private String bmKey(ReactionTargetVO target, long shard) {
        return KEY_BM_PREFIX + target.hashTag() + ":" + shard;
    }

    private String cntKey(ReactionTargetVO target) {
        return KEY_CNT_PREFIX + target.hashTag();
    }

    private String windowMsKey(ReactionTargetVO target) {
        return KEY_WINDOW_MS_PREFIX + target.hashTag();
    }

    private String recoveryCheckpointKey(String targetType, String reactionType) {
        return KEY_RECOVERY_CP_PREFIX + (targetType == null ? "" : targetType) + ":" + (reactionType == null ? "" : reactionType);
    }

    private String hotkeyKey(ReactionTargetVO target) {
        return HOTKEY_PREFIX + target.hashTag();
    }

    private long redisCountOnly(ReactionTargetVO target) {
        String raw = stringRedisTemplate.opsForValue().get(cntKey(target));
        Long v = parseLong(raw);
        return v == null || v < 0 ? 0L : v;
    }

    private String targetTypeCode(ReactionTargetVO target) {
        return target == null || target.getTargetType() == null ? "" : target.getTargetType().getCode();
    }

    private String reactionTypeCode(ReactionTargetVO target) {
        return target == null || target.getReactionType() == null ? "" : target.getReactionType().getCode();
    }

    private boolean isHotKeySafe(String hotkey) {
        try {
            return hotKeyStoreBridge.isHotKey(hotkey);
        } catch (Exception e) {
            log.warn("jd-hotkey isHotKey failed, hotkey={}", hotkey, e);
            return false;
        }
    }

    private Long parseLong(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private long listLong(List<?> list, int idx, long defaultVal) {
        if (list == null || idx < 0 || idx >= list.size()) {
            return defaultVal;
        }
        Long v = parseLong(list.get(idx));
        return v == null ? defaultVal : v;
    }

    private long clamp(long v, long min, long max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private static <T> DefaultRedisScript<T> script(String text, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setResultType(resultType);
        script.setScriptText(text);
        return script;
    }

    private static final String LUA_APPLY_ATOMIC = ""
            + "local bmKey = KEYS[1]\n"
            + "local cntKey = KEYS[2]\n"
            + "\n"
            + "local desiredState = ARGV[1]\n"
            + "local offset = tonumber(ARGV[2])\n"
            + "\n"
            + "local delta = 0\n"
            + "local current = redis.call('GET', cntKey)\n"
            + "if (not current) then\n"
            + "  redis.call('SET', cntKey, '0')\n"
            + "end\n"
            + "\n"
            + "if desiredState == '1' then\n"
            + "  local old = redis.call('SETBIT', bmKey, offset, 1)\n"
            + "  if old == 0 then\n"
            + "    redis.call('INCR', cntKey)\n"
            + "    delta = 1\n"
            + "  end\n"
            + "else\n"
            + "  local old = redis.call('SETBIT', bmKey, offset, 0)\n"
            + "  if old == 1 then\n"
            + "    redis.call('DECR', cntKey)\n"
            + "    delta = -1\n"
            + "  end\n"
            + "end\n"
            + "\n"
            + "local updated = tonumber(redis.call('GET', cntKey) or '0')\n"
            + "if updated < 0 then\n"
            + "  updated = 0\n"
            + "  redis.call('SET', cntKey, '0')\n"
            + "end\n"
            + "\n"
            + "return {updated, delta}\n";

    private static final DefaultRedisScript<List> APPLY_ATOMIC_SCRIPT = script(LUA_APPLY_ATOMIC, List.class);

    private static final String LUA_APPLY_RECOVERY = ""
            + "local bmKey = KEYS[1]\n"
            + "local cntKey = KEYS[2]\n"
            + "\n"
            + "local desiredState = ARGV[1]\n"
            + "local offset = tonumber(ARGV[2])\n"
            + "\n"
            + "local current = redis.call('GET', cntKey)\n"
            + "if (not current) then\n"
            + "  redis.call('SET', cntKey, '0')\n"
            + "end\n"
            + "\n"
            + "if desiredState == '1' then\n"
            + "  local old = redis.call('SETBIT', bmKey, offset, 1)\n"
            + "  if old == 0 then\n"
            + "    redis.call('INCR', cntKey)\n"
            + "  end\n"
            + "else\n"
            + "  local old = redis.call('SETBIT', bmKey, offset, 0)\n"
            + "  if old == 1 then\n"
            + "    redis.call('DECR', cntKey)\n"
            + "  end\n"
            + "end\n"
            + "\n"
            + "local updated = tonumber(redis.call('GET', cntKey) or '0')\n"
            + "if updated < 0 then\n"
            + "  updated = 0\n"
            + "  redis.call('SET', cntKey, '0')\n"
            + "end\n"
            + "\n"
            + "return {updated}\n";

    private static final DefaultRedisScript<List> APPLY_RECOVERY_SCRIPT = script(LUA_APPLY_RECOVERY, List.class);
}
