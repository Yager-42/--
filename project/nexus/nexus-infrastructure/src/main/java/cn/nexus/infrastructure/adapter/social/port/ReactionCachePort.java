package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.infrastructure.dao.social.IInteractionReactionCountDao;
import cn.nexus.infrastructure.support.SingleFlight;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jd.platform.hotkey.client.callback.JdHotKeyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * 点赞缓存端口 Redis 实现。
 *
 * @author rr
 * @author codex
 * @since 2026-01-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactionCachePort implements IReactionCachePort {

    private static final String KEY_BM_PREFIX = "interact:reaction:bm:";
    private static final long BIT_SHARD_SIZE = 1_000_000L;
    private static final String KEY_CNT_PREFIX = "interact:reaction:cnt:";
    private static final String KEY_OPS_PREFIX = "interact:reaction:ops:";
    private static final String KEY_OPS_PROCESSING_PREFIX = "interact:reaction:ops:processing:";
    private static final String KEY_SYNC_PREFIX = "interact:reaction:sync:";
    private static final String KEY_LAST_SYNC_PREFIX = "interact:reaction:last_sync:";
    private static final String KEY_WINDOW_MS_PREFIX = "interact:reaction:window_ms:";

    private static final long WINDOW_MS_MIN = 1_000L;
    private static final long WINDOW_MS_MAX = 600_000L;

    private static final String HOTKEY_PREFIX = "like__";

    private static final int L1_MAX_SIZE = 100_000;
    private static final Duration L1_TTL = Duration.ofSeconds(2);

    private final StringRedisTemplate stringRedisTemplate;
    private final IInteractionReactionCountDao interactionReactionCountDao;

    /**
     * 只缓存 count：热点读走 L1，短 TTL。
     */
    private final Cache<String, Long> countCache = Caffeine.newBuilder()
            .maximumSize(L1_MAX_SIZE)
            .expireAfterWrite(L1_TTL)
            .build();

    private final SingleFlight singleFlight = new SingleFlight();

    /**
     * 原子应用状态和计数。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param desiredState desiredState 参数。类型：{@code int}
     * @param syncTtlSec syncTtlSec 参数。类型：{@code int}
     * @return 处理结果。类型：{@link ReactionApplyResultVO}
     */
    @Override
    public ReactionApplyResultVO applyAtomic(Long userId, ReactionTargetVO target, int desiredState, int syncTtlSec) {
        if (userId == null || target == null) {
            return ReactionApplyResultVO.builder().currentCount(0L).delta(0).firstPending(false).build();
        }
        if (userId < 0) {
            return ReactionApplyResultVO.builder().currentCount(0L).delta(0).firstPending(false).build();
        }

        long shard = userId / BIT_SHARD_SIZE;
        long offset = userId % BIT_SHARD_SIZE;

        List<String> keys = List.of(bmKey(target, shard), cntKey(target), opsKey(target), syncKey(target));
        List<String> argv = List.of(
                userId.toString(),
                String.valueOf(desiredState),
                String.valueOf(offset),
                String.valueOf(Math.max(1, syncTtlSec))
        );

        List<?> res = stringRedisTemplate.execute(APPLY_ATOMIC_SCRIPT, keys, argv.toArray());
        long currentCount = listLong(res, 0, 0L);
        int delta = (int) listLong(res, 1, 0L);
        boolean firstPending = listLong(res, 2, 0L) == 1L;

        if (firstPending) {
            // 新写入必然让这个 key 热起来；先清理一次避免短时间内返回旧值。
            countCache.invalidate(hotkeyKey(target));
        }

        return ReactionApplyResultVO.builder()
                .currentCount(currentCount)
                .delta(delta)
                .firstPending(firstPending)
                .build();
    }

    /**
     * 记录操作快照。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean snapshotOps(ReactionTargetVO target) {
        if (target == null) {
            return false;
        }
        Long moved = stringRedisTemplate.execute(
                SNAPSHOT_OPS_SCRIPT,
                List.of(opsKey(target), processingKey(target))
        );
        return moved != null && moved > 0;
    }

    /**
     * 读取操作快照。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @return 处理结果。类型：{@link Map}
     */
    @Override
    public Map<Long, Integer> readOpsSnapshot(ReactionTargetVO target) {
        if (target == null) {
            return Collections.emptyMap();
        }
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(processingKey(target));
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> out = new HashMap<>(raw.size());
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            Long userId = parseLong(e.getKey());
            Integer desired = parseInt(e.getValue());
            if (userId == null || desired == null) {
                continue;
            }
            out.put(userId, desired);
        }
        return out;
    }

    /**
     * 清理操作快照。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     */
    @Override
    public void clearOpsSnapshot(ReactionTargetVO target) {
        if (target == null) {
            return;
        }
        stringRedisTemplate.delete(processingKey(target));
    }

    /**
     * 读取计数。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @return 处理结果。类型：{@code long}
     */
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

        long cnt = singleFlight.execute(target.hashTag(), () -> redisGetCntOrRebuild(target));
        if (hot) {
            countCache.put(hotkey, cnt);
        }
        return cnt;
    }

    /**
     * 批量读取计数。
     *
     * @param targets targets 参数。类型：{@link List}
     * @return 处理结果。类型：{@link Map}
     */
    @Override
    public Map<String, Long> batchGetCount(List<ReactionTargetVO> targets) {
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }

        List<String> keys = new ArrayList<>(targets.size());
        for (ReactionTargetVO target : targets) {
            if (target == null) {
                keys.add(null);
                continue;
            }
            keys.add(cntKey(target));
        }

        List<String> values;
        try {
            values = stringRedisTemplate.opsForValue().multiGet(keys);
        } catch (Exception e) {
            log.warn("batchGetCount redis multiGet failed", e);
            values = null;
        }

        Map<String, Long> result = new HashMap<>(targets.size());
        List<ReactionTargetVO> missTargets = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            ReactionTargetVO target = targets.get(i);
            if (target == null) {
                continue;
            }
            String raw = values != null && i < values.size() ? values.get(i) : null;
            Long count = parseLong(raw);
            if (count != null && count >= 0) {
                result.put(target.hashTag(), count);
                continue;
            }
            missTargets.add(target);
        }

        for (ReactionTargetVO target : missTargets) {
            String targetType = target.getTargetType() == null ? null : target.getTargetType().getCode();
            Long targetId = target.getTargetId();
            String reactionType = target.getReactionType() == null ? null : target.getReactionType().getCode();
            long rebuilt;
            if (targetType == null || targetType.isBlank() || targetId == null || reactionType == null || reactionType.isBlank()) {
                rebuilt = 0L;
            } else {
                Long dbCount = interactionReactionCountDao.selectCount(targetType, targetId, reactionType);
                rebuilt = dbCount == null ? 0L : Math.max(0L, dbCount);
            }
            try {
                stringRedisTemplate.opsForValue().set(cntKey(target), String.valueOf(rebuilt));
            } catch (Exception ignored) {
                // ignore
            }
            result.put(target.hashTag(), rebuilt);
        }
        return result;
    }

    /**
     * 从 Redis 读取计数。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @return 处理结果。类型：{@code long}
     */
    @Override
    public long getCountFromRedis(ReactionTargetVO target) {
        if (target == null) {
            return 0L;
        }
        return redisGetCntOrRebuild(target);
    }

    /**
     * 读取互动状态。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean getState(Long userId, ReactionTargetVO target) {
        if (userId == null || target == null) {
            return false;
        }
        if (userId < 0) {
            return false;
        }
        long shard = userId / BIT_SHARD_SIZE;
        long offset = userId % BIT_SHARD_SIZE;
        Boolean bit = stringRedisTemplate.opsForValue().getBit(bmKey(target, shard), offset);
        return Boolean.TRUE.equals(bit);
    }

    /**
     * 判断位图分片是否存在。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean bitmapShardExists(Long userId, ReactionTargetVO target) {
        if (userId == null || target == null || userId < 0) {
            return false;
        }
        long shard = userId / BIT_SHARD_SIZE;
        Boolean exists = stringRedisTemplate.hasKey(bmKey(target, shard));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 写入互动状态。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param state state 参数。类型：{@code boolean}
     */
    @Override
    public void setState(Long userId, ReactionTargetVO target, boolean state) {
        if (userId == null || target == null || userId < 0) {
            return;
        }
        long shard = userId / BIT_SHARD_SIZE;
        long offset = userId % BIT_SHARD_SIZE;
        stringRedisTemplate.opsForValue().setBit(bmKey(target, shard), offset, state);
    }

    /**
     * 写入互动计数。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param count count 参数。类型：{@code long}
     */
    @Override
    public void setCount(ReactionTargetVO target, long count) {
        if (target == null) {
            return;
        }
        long safe = Math.max(0L, count);
        String cntKey = cntKey(target);
        stringRedisTemplate.opsForValue().set(cntKey, String.valueOf(safe));
        countCache.invalidate(hotkeyKey(target));
    }

    /**
     * 获取窗口大小。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param defaultMs defaultMs 参数。类型：{@code long}
     * @return 处理结果。类型：{@code long}
     */
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

    /**
     * 标记待同步。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param ttlSec ttlSec 参数。类型：{@code int}
     */
    @Override
    public void setSyncPending(ReactionTargetVO target, int ttlSec) {
        if (target == null) {
            return;
        }
        int ttl = Math.max(1, ttlSec);
        stringRedisTemplate.opsForValue().set(syncKey(target), "PENDING", Duration.ofSeconds(ttl));
    }

    /**
     * 清理同步标记。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     */
    @Override
    public void clearSyncFlag(ReactionTargetVO target) {
        if (target == null) {
            return;
        }
        stringRedisTemplate.delete(syncKey(target));
    }

    /**
     * 记录最近同步时间。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param epochMillis epochMillis 参数。类型：{@code long}
     */
    @Override
    public void setLastSyncTime(ReactionTargetVO target, long epochMillis) {
        if (target == null) {
            return;
        }
        stringRedisTemplate.opsForValue().set(lastSyncKey(target), String.valueOf(epochMillis));
    }

    /**
     * 判断操作快照是否存在。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean existsOps(ReactionTargetVO target) {
        if (target == null) {
            return false;
        }
        Boolean exists = stringRedisTemplate.hasKey(opsKey(target));
        return Boolean.TRUE.equals(exists);
    }

    private String bmKey(ReactionTargetVO target, long shard) {
        return KEY_BM_PREFIX + target.hashTag() + ":" + shard;
    }

    private String cntKey(ReactionTargetVO target) {
        return KEY_CNT_PREFIX + target.hashTag();
    }

    private String opsKey(ReactionTargetVO target) {
        return KEY_OPS_PREFIX + target.hashTag();
    }

    private String processingKey(ReactionTargetVO target) {
        return KEY_OPS_PROCESSING_PREFIX + target.hashTag();
    }

    private String syncKey(ReactionTargetVO target) {
        return KEY_SYNC_PREFIX + target.hashTag();
    }

    private String lastSyncKey(ReactionTargetVO target) {
        return KEY_LAST_SYNC_PREFIX + target.hashTag();
    }

    private String windowMsKey(ReactionTargetVO target) {
        return KEY_WINDOW_MS_PREFIX + target.hashTag();
    }

    private String hotkeyKey(ReactionTargetVO target) {
        return HOTKEY_PREFIX + target.hashTag();
    }

    private long redisGetCntOrRebuild(ReactionTargetVO target) {
        String cntKey = cntKey(target);
        String raw = stringRedisTemplate.opsForValue().get(cntKey);
        Long v = parseLong(raw);
        if (v != null && v >= 0) {
            return v;
        }

        // cntKey 丢失或损坏：从 DB 的 interaction_reaction_count 回表读 count 并回填。
        String targetType = target.getTargetType() == null ? null : target.getTargetType().getCode();
        Long targetId = target.getTargetId();
        String reactionType = target.getReactionType() == null ? null : target.getReactionType().getCode();
        if (targetType == null || targetType.isBlank() || targetId == null || reactionType == null || reactionType.isBlank()) {
            stringRedisTemplate.opsForValue().set(cntKey, "0");
            return 0L;
        }
        Long dbCount = interactionReactionCountDao.selectCount(targetType, targetId, reactionType);
        long rebuilt = dbCount == null ? 0L : Math.max(0L, dbCount);
        stringRedisTemplate.opsForValue().set(cntKey, String.valueOf(rebuilt));
        return rebuilt;
    }

    private boolean isHotKeySafe(String hotkey) {
        try {
            return JdHotKeyStore.isHotKey(hotkey);
        } catch (Exception e) {
            // 外部依赖不可用时，热点治理直接关闭（不影响主链路）。
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

    private Integer parseInt(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
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
            + "local opsKey = KEYS[3]\n"
            + "local syncKey = KEYS[4]\n"
            + "\n"
            + "local userId = ARGV[1]\n"
            + "local desiredState = ARGV[2]\n"
            + "local offset = tonumber(ARGV[3])\n"
            + "local syncTtlSec = tonumber(ARGV[4])\n"
            + "\n"
            + "local delta = 0\n"
            + "\n"
            + "-- cntKey 不存在时：先设成 0（避免 DECR 直接变负数）；后续读侧会从 DB count 表回填纠正\n"
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
            + "-- last-write-wins：延迟落库时按 userId 覆盖即可\n"
            + "redis.call('HSET', opsKey, userId, desiredState)\n"
            + "\n"
            + "-- 只在首次置 pending 时返回 1，避免重复投递延迟任务\n"
            + "local firstPending = redis.call('SET', syncKey, 'PENDING', 'NX', 'EX', syncTtlSec)\n"
            + "\n"
            + "-- 防御：cntKey 可能被误删/误写，出现负数时拉回到 0（并让后续同步再对齐 DB）\n"
            + "local updated = tonumber(redis.call('GET', cntKey) or '0')\n"
            + "if updated < 0 then\n"
            + "  updated = 0\n"
            + "  redis.call('SET', cntKey, '0')\n"
            + "end\n"
            + "\n"
            + "local fp = 0\n"
            + "if firstPending then fp = 1 end\n"
            + "return {updated, delta, fp}\n";

    private static final String LUA_SNAPSHOT_OPS = ""
            + "local opsKey = KEYS[1]\n"
            + "local processingKey = KEYS[2]\n"
            + "\n"
            + "-- 如果 processingKey 还在，说明上次同步没清干净：不覆盖，直接复用快照继续处理\n"
            + "if redis.call('EXISTS', processingKey) == 1 then\n"
            + "  return 1\n"
            + "end\n"
            + "\n"
            + "if redis.call('EXISTS', opsKey) == 0 then\n"
            + "  return 0\n"
            + "end\n"
            + "\n"
            + "redis.call('RENAME', opsKey, processingKey)\n"
            + "return 1\n";

    private static final DefaultRedisScript<List> APPLY_ATOMIC_SCRIPT = script(LUA_APPLY_ATOMIC, List.class);
    private static final DefaultRedisScript<Long> SNAPSHOT_OPS_SCRIPT = script(LUA_SNAPSHOT_OPS, Long.class);
}
