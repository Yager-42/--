package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.ReactionBatchStateVO;
import cn.nexus.domain.social.model.valobj.ReactionStateItemVO;
import cn.nexus.domain.social.model.valobj.ReactionStateVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionToggleResultVO;
import cn.nexus.infrastructure.dao.social.ILikeCountDao;
import cn.nexus.infrastructure.dao.social.ILikeDao;
import cn.nexus.infrastructure.dao.social.po.LikeCountPO;
import cn.nexus.infrastructure.dao.social.po.LikePO;
import cn.nexus.infrastructure.dao.social.po.LikeTargetPO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 点赞/态势仓储 Redis 实现。
 *
 * <p>核心目标：把“幂等 + 计数 + touch + win 状态机”收敛到 Lua 脚本里，一次往返完成，避免 Java 写一堆 if/else。</p>
 */
@Repository
@RequiredArgsConstructor
public class ReactionRepository implements IReactionRepository {

    private final StringRedisTemplate stringRedisTemplate;
    private final ILikeDao likeDao;
    private final ILikeCountDao likeCountDao;

    /**
     * Lua 返回：{delta, currentCount, needSchedule}
     */
    private static final DefaultRedisScript<List> TOGGLE_SCRIPT = buildToggleScript();

    /**
     * Lua 返回：0/1（是否需要重排队）。
     */
    private static final DefaultRedisScript<Long> FINALIZE_SCRIPT = buildFinalizeScript();

    /**
     * Lua 返回：0/1（是否成功解锁）。
     *
     * <p>用 Lua 做 compare-and-delete，避免 GET+DEL 的竞态误删别人刚拿到的锁。</p>
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = buildUnlockScript();

    @Override
    public ReactionToggleResultVO toggle(Long userId, String targetType, Long targetId, String action, long syncTtlSeconds) {
        if (userId == null || targetId == null || targetType == null || action == null) {
            return ReactionToggleResultVO.builder().delta(0L).currentCount(0L).needSchedule(false).build();
        }

        String userKey = userKey(userId);
        String countKey = countKey(targetType, targetId);
        String touchKey = touchKey(targetType, targetId);
        String winKey = winKey(targetType, targetId);
        String member = member(targetType, targetId);

        List<String> keys = List.of(userKey, countKey, touchKey, winKey);
        List<Object> res = stringRedisTemplate.execute(
                TOGGLE_SCRIPT,
                keys,
                member,
                action,
                String.valueOf(userId),
                String.valueOf(Math.max(1L, syncTtlSeconds))
        );

        if (res == null || res.size() < 3) {
            return ReactionToggleResultVO.builder().delta(0L).currentCount(0L).needSchedule(false).build();
        }

        Long delta = toLong(res.get(0));
        Long currentCount = toLong(res.get(1));
        Long needSchedule = toLong(res.get(2));
        return ReactionToggleResultVO.builder()
                .delta(delta == null ? 0L : delta)
                .currentCount(currentCount == null ? 0L : currentCount)
                .needSchedule(needSchedule != null && needSchedule == 1L)
                .build();
    }

    @Override
    public ReactionStateVO getState(Long userId, String targetType, Long targetId) {
        if (userId == null || targetId == null || targetId <= 0 || targetType == null || targetType.isBlank()) {
            return ReactionStateVO.builder().likeCount(0L).likedByMe(false).build();
        }

        Long likeCount = getOrLoadCount(targetType, targetId);
        boolean likedByMe = getOrLoadLikedByMe(userId, targetType, targetId);
        return ReactionStateVO.builder()
                .likeCount(likeCount == null ? 0L : likeCount)
                .likedByMe(likedByMe)
                .build();
    }

    @Override
    public ReactionBatchStateVO getBatchState(Long userId, List<ReactionTargetVO> targets) {
        if (userId == null || targets == null || targets.isEmpty()) {
            return ReactionBatchStateVO.builder().items(Collections.emptyList()).build();
        }

        // 1) 先准备“有效 target 列表”与下标映射：保证输出顺序与输入 targets 对齐。
        List<Integer> validIndexes = new ArrayList<>();
        List<ReactionTargetVO> validTargets = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            ReactionTargetVO t = targets.get(i);
            if (t == null || t.getTargetId() == null || t.getTargetId() <= 0 || t.getTargetType() == null || t.getTargetType().isBlank()) {
                continue;
            }
            validIndexes.add(i);
            validTargets.add(t);
        }

        // 2) likeCount：Redis MGET，miss 批量回源 DB 并回填 Redis（不设 TTL，避免用户可见“清零”）。
        Map<Integer, Long> indexToCount = loadCountsBatch(validIndexes, validTargets);

        // 3) likedByMe：优先 Redis set；若 userKey 不存在则对本批次 targets 回源 DB 并回填正例。
        Map<Integer, Boolean> indexToLiked = loadLikedByMeBatch(userId, validIndexes, validTargets);

        // 4) 组装输出：顺序与输入对齐；非法 target 返回 0/false。
        List<ReactionStateItemVO> items = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            ReactionTargetVO t = targets.get(i);
            Long targetId = t == null ? null : t.getTargetId();
            String targetType = t == null ? null : t.getTargetType();
            Long likeCount = indexToCount.getOrDefault(i, 0L);
            boolean liked = Boolean.TRUE.equals(indexToLiked.get(i));
            items.add(ReactionStateItemVO.builder()
                    .targetId(targetId)
                    .targetType(targetType)
                    .likeCount(likeCount)
                    .likedByMe(liked)
                    .build());
        }
        return ReactionBatchStateVO.builder().items(items).build();
    }

    @Override
    public boolean flush(String targetType, Long targetId, long syncTtlSeconds, long flushLockSeconds) {
        if (targetType == null || targetType.isBlank() || targetId == null || targetId <= 0) {
            return false;
        }

        String lockKey = flushLockKey(targetType, targetId);
        String lockVal = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockVal, Duration.ofSeconds(Math.max(1L, flushLockSeconds)));
        // setIfAbsent 在某些异常场景可能返回 null，这里必须“非 true 即失败”，否则会在未持锁时进入 flush（并发写错）。
        if (!Boolean.TRUE.equals(locked)) {
            return false;
        }

        try {
            // 1) 计数绝对值落库：以 Redis 为准（重复 flush 也是幂等的）。
            Long cachedCount = parseLong(stringRedisTemplate.opsForValue().get(countKey(targetType, targetId)));
            long count = cachedCount == null ? 0L : cachedCount;
            LikeCountPO countPO = new LikeCountPO();
            countPO.setTargetType(targetType);
            countPO.setTargetId(targetId);
            countPO.setLikeCount(count);
            likeCountDao.upsert(countPO);

            // 2) touch 快照：用固定 snapKey 做 RENAMENX。
            //
            // 为什么不用“带 token 的一次性 snapKey”？
            // - flush 中途失败（例如 DB 挂了）时，带 token 的 snapKey 会变成“孤儿 key”，后续 flush 找不到它，导致那一批状态永远不落库。
            // - 固定 snapKey 可以让后续 flush 直接继续处理，失败可重试，不丢数据（只要 key 还没过期）。
            String touchKey = touchKey(targetType, targetId);
            String snapKey = touchSnapshotKey(targetType, targetId);
            boolean hasSnapshot = Boolean.TRUE.equals(stringRedisTemplate.hasKey(snapKey));
            if (!hasSnapshot) {
                Boolean touchExists = stringRedisTemplate.hasKey(touchKey);
                hasSnapshot = Boolean.TRUE.equals(touchExists) && Boolean.TRUE.equals(stringRedisTemplate.renameIfAbsent(touchKey, snapKey));
            }

            if (hasSnapshot) {
                Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(snapKey);
                if (entries != null && !entries.isEmpty()) {
                    List<LikePO> rows = new ArrayList<>(entries.size());
                    for (Map.Entry<Object, Object> e : entries.entrySet()) {
                        Long uid = parseLong(String.valueOf(e.getKey()));
                        Long status = parseLong(String.valueOf(e.getValue()));
                        if (uid == null || status == null) {
                            continue;
                        }
                        LikePO po = new LikePO();
                        po.setUserId(uid);
                        po.setTargetType(targetType);
                        po.setTargetId(targetId);
                        po.setStatus(status == 1L ? 1 : 0);
                        rows.add(po);
                    }
                    if (!rows.isEmpty()) {
                        batchUpsertLikes(rows);
                    }
                }
                stringRedisTemplate.delete(snapKey);
            }

            // 3) finalize：原子推进 win 状态机；必要时返回 reschedule=true 让上层重排队。
            Long res = stringRedisTemplate.execute(
                    FINALIZE_SCRIPT,
                    List.of(winKey(targetType, targetId)),
                    String.valueOf(Math.max(1L, syncTtlSeconds))
            );
            return res != null && res == 1L;
        } finally {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockVal);
        }
    }

    private void batchUpsertLikes(List<LikePO> rows) {
        // 保护数据库：热点目标在一个窗口内可能积累大量用户状态，一次性拼成超长 SQL 会直接炸。
        final int batchSize = 500;
        for (int i = 0; i < rows.size(); i += batchSize) {
            int end = Math.min(i + batchSize, rows.size());
            likeDao.batchUpsert(rows.subList(i, end));
        }
    }

    private Long getOrLoadCount(String targetType, Long targetId) {
        String key = countKey(targetType, targetId);
        Long cached = parseLong(stringRedisTemplate.opsForValue().get(key));
        if (cached != null) {
            return cached;
        }

        LikeCountPO po = likeCountDao.selectOne(targetType, targetId);
        long loaded = po == null || po.getLikeCount() == null ? 0L : po.getLikeCount();
        // countKey 不设置 TTL：避免热点内容“清零”造成用户可见错误。
        stringRedisTemplate.opsForValue().set(key, String.valueOf(loaded));
        return loaded;
    }

    private boolean getOrLoadLikedByMe(Long userId, String targetType, Long targetId) {
        String key = userKey(userId);
        String m = member(targetType, targetId);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, m));
        }

        Integer status = likeDao.selectStatus(userId, targetType, targetId);
        boolean liked = status != null && status == 1;
        if (liked) {
            // 只回填正例：避免把“没点过赞”的全量塞进 Redis。
            stringRedisTemplate.opsForSet().add(key, m);
        }
        return liked;
    }

    private Map<Integer, Long> loadCountsBatch(List<Integer> validIndexes, List<ReactionTargetVO> validTargets) {
        Map<Integer, Long> indexToCount = new HashMap<>();
        if (validTargets.isEmpty()) {
            return indexToCount;
        }

        List<String> keys = new ArrayList<>(validTargets.size());
        for (ReactionTargetVO t : validTargets) {
            keys.add(countKey(t.getTargetType(), t.getTargetId()));
        }
        List<String> cached = stringRedisTemplate.opsForValue().multiGet(keys);

        // 记录 miss 的唯一 target（用于一次 DB 查询）。
        Map<String, LikeTargetPO> missTargets = new HashMap<>();
        for (int i = 0; i < validTargets.size(); i++) {
            int originalIndex = validIndexes.get(i);
            Long count = cached == null || cached.size() <= i ? null : parseLong(cached.get(i));
            if (count != null) {
                indexToCount.put(originalIndex, count);
                continue;
            }
            ReactionTargetVO t = validTargets.get(i);
            String m = member(t.getTargetType(), t.getTargetId());
            if (missTargets.containsKey(m)) {
                continue;
            }
            LikeTargetPO po = new LikeTargetPO();
            po.setTargetType(t.getTargetType());
            po.setTargetId(t.getTargetId());
            missTargets.put(m, po);
        }

        if (missTargets.isEmpty()) {
            return indexToCount;
        }

        List<LikeCountPO> dbRows = likeCountDao.selectByTargets(new ArrayList<>(missTargets.values()));
        Map<String, Long> dbCountMap = new HashMap<>();
        if (dbRows != null) {
            for (LikeCountPO row : dbRows) {
                if (row == null || row.getTargetType() == null || row.getTargetId() == null) {
                    continue;
                }
                dbCountMap.put(member(row.getTargetType(), row.getTargetId()), row.getLikeCount() == null ? 0L : row.getLikeCount());
            }
        }

        // 回填 Redis（不设 TTL），并为本次请求组装完整 counts。
        for (Map.Entry<String, LikeTargetPO> e : missTargets.entrySet()) {
            String m = e.getKey();
            LikeTargetPO t = e.getValue();
            Long cnt = dbCountMap.getOrDefault(m, 0L);
            stringRedisTemplate.opsForValue().set(countKey(t.getTargetType(), t.getTargetId()), String.valueOf(cnt));
        }
        for (int i = 0; i < validTargets.size(); i++) {
            int originalIndex = validIndexes.get(i);
            if (indexToCount.containsKey(originalIndex)) {
                continue;
            }
            ReactionTargetVO t = validTargets.get(i);
            Long cnt = dbCountMap.getOrDefault(member(t.getTargetType(), t.getTargetId()), 0L);
            indexToCount.put(originalIndex, cnt);
        }
        return indexToCount;
    }

    private Map<Integer, Boolean> loadLikedByMeBatch(Long userId, List<Integer> validIndexes, List<ReactionTargetVO> validTargets) {
        Map<Integer, Boolean> indexToLiked = new HashMap<>();
        if (validTargets.isEmpty()) {
            return indexToLiked;
        }

        String key = userKey(userId);
        boolean userKeyExists = Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        if (userKeyExists) {
            List<String> members = new ArrayList<>(validTargets.size());
            for (ReactionTargetVO t : validTargets) {
                members.add(member(t.getTargetType(), t.getTargetId()));
            }
            @SuppressWarnings("unchecked")
            List<Object> res = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    // StringRedisTemplate 固定是 <String, String>，这里把泛型收敛回来，避免 K 推断失败导致编译不过。
                    @SuppressWarnings("unchecked")
                    RedisOperations<String, String> stringOps = (RedisOperations<String, String>) operations;
                    for (String m : members) {
                        stringOps.opsForSet().isMember(key, m);
                    }
                    return null;
                }
            });
            for (int i = 0; i < validTargets.size(); i++) {
                int originalIndex = validIndexes.get(i);
                Object r = res == null || res.size() <= i ? null : res.get(i);
                indexToLiked.put(originalIndex, toBool(r));
            }
            return indexToLiked;
        }

        // userKey 不存在：回源 DB（只查本批次 targets 的正例），并回填 Redis 正例。
        Map<String, LikeTargetPO> queryTargets = new HashMap<>();
        for (ReactionTargetVO t : validTargets) {
            String m = member(t.getTargetType(), t.getTargetId());
            if (queryTargets.containsKey(m)) {
                continue;
            }
            LikeTargetPO po = new LikeTargetPO();
            po.setTargetType(t.getTargetType());
            po.setTargetId(t.getTargetId());
            queryTargets.put(m, po);
        }
        List<LikePO> likedRows = likeDao.selectLikedTargets(userId, new ArrayList<>(queryTargets.values()));
        Set<String> likedMembers = new HashSet<>();
        if (likedRows != null) {
            for (LikePO row : likedRows) {
                if (row == null || row.getTargetType() == null || row.getTargetId() == null) {
                    continue;
                }
                likedMembers.add(member(row.getTargetType(), row.getTargetId()));
            }
        }
        if (!likedMembers.isEmpty()) {
            stringRedisTemplate.opsForSet().add(key, likedMembers.toArray(new String[0]));
        }
        for (int i = 0; i < validTargets.size(); i++) {
            int originalIndex = validIndexes.get(i);
            ReactionTargetVO t = validTargets.get(i);
            indexToLiked.put(originalIndex, likedMembers.contains(member(t.getTargetType(), t.getTargetId())));
        }
        return indexToLiked;
    }

    private static Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean toBool(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj instanceof Boolean b) {
            return b;
        }
        Long l = toLong(obj);
        if (l != null) {
            return l == 1L;
        }
        return "true".equalsIgnoreCase(String.valueOf(obj));
    }

    private static DefaultRedisScript<List> buildToggleScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setResultType(List.class);
        script.setScriptText("""
                -- 点赞写链路：原子完成 幂等判定/计数更新/touch 写入/win 状态机。
                --
                -- KEYS[1] userKey  = like:user:{userId}           (SET)    幂等根
                -- KEYS[2] countKey = like:count:{type}:{id}       (STRING) 用户可见计数
                -- KEYS[3] touchKey = like:touch:{type}:{id}       (HASH)   窗口内用户最终状态
                -- KEYS[4] winKey   = like:win:{type}:{id}         (STRING) 窗口调度状态 0/1
                --
                -- ARGV[1] member   = {targetType}:{targetId}
                -- ARGV[2] action   = ADD/REMOVE
                -- ARGV[3] userId   = field
                -- ARGV[4] ttlSec   = sync ttl seconds
                local userKey = KEYS[1]
                local countKey = KEYS[2]
                local touchKey = KEYS[3]
                local winKey = KEYS[4]
                
                local member = ARGV[1]
                local action = ARGV[2]
                local userId = ARGV[3]
                local ttl = tonumber(ARGV[4]) or 0
                
                local existed = redis.call('SISMEMBER', userKey, member)
                local delta = 0
                local newStatus = nil
                
                if action == 'ADD' then
                  if existed == 0 then
                    redis.call('SADD', userKey, member)
                    delta = 1
                    newStatus = 1
                  end
                elseif action == 'REMOVE' then
                  if existed == 1 then
                    redis.call('SREM', userKey, member)
                    delta = -1
                    newStatus = 0
                  end
                end
                
                local current = tonumber(redis.call('GET', countKey) or '0')
                local newCount = current + delta
                if newCount < 0 then
                  newCount = 0
                end
                
                local needSchedule = 0
                
                -- 只有真正发生变化（delta != 0）才写 touch/win；幂等请求不制造脏数据与无效 flush。
                if delta ~= 0 then
                  redis.call('SET', countKey, tostring(newCount))
                  redis.call('HSET', touchKey, userId, tostring(newStatus))
                  if ttl > 0 then
                    redis.call('EXPIRE', touchKey, ttl)
                  end
                
                  local winExists = redis.call('EXISTS', winKey)
                  if winExists == 0 then
                    redis.call('SET', winKey, '0')
                    if ttl > 0 then
                      redis.call('EXPIRE', winKey, ttl)
                    end
                    needSchedule = 1
                  else
                    redis.call('SET', winKey, '1')
                    if ttl > 0 then
                      redis.call('EXPIRE', winKey, ttl)
                    end
                    needSchedule = 0
                  end
                end
                
                return {delta, newCount, needSchedule}
                """);
        return script;
    }

    private static DefaultRedisScript<Long> buildFinalizeScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                -- flush finalize：原子推进 like:win 状态机，保证“不丢最后一次写入”。
                --
                -- KEYS[1] winKey = like:win:{type}:{id}
                -- ARGV[1] ttlSec = sync ttl seconds
                local winKey = KEYS[1]
                local ttl = tonumber(ARGV[1]) or 0
                
                local v = redis.call('GET', winKey)
                if not v then
                  return 0
                end
                
                -- win=1：flush 期间又发生了新变化，必须再跑一轮（把 1 复位为 0 并要求重排队）。
                if v == '1' then
                  redis.call('SET', winKey, '0')
                  if ttl > 0 then
                    redis.call('EXPIRE', winKey, ttl)
                  end
                  return 1
                end
                
                -- win=0：窗口内没有新变化，删除 winKey；下一次写请求会重新创建窗口并调度 flush。
                redis.call('DEL', winKey)
                return 0
                """);
        return script;
    }

    private static DefaultRedisScript<Long> buildUnlockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                -- flush lock unlock：compare-and-delete，避免误删别人刚拿到的锁。
                -- KEYS[1] lockKey
                -- ARGV[1] lockVal
                local lockKey = KEYS[1]
                local lockVal = ARGV[1]
                local v = redis.call('GET', lockKey)
                if v and v == lockVal then
                  redis.call('DEL', lockKey)
                  return 1
                end
                return 0
                """);
        return script;
    }

    private static Long toLong(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Long l) {
            return l;
        }
        if (obj instanceof Integer i) {
            return i.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(obj));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String userKey(Long userId) {
        return "like:user:" + userId;
    }

    private static String countKey(String targetType, Long targetId) {
        return "like:count:" + targetType + ":" + targetId;
    }

    private static String touchKey(String targetType, Long targetId) {
        return "like:touch:" + targetType + ":" + targetId;
    }

    private static String winKey(String targetType, Long targetId) {
        return "like:win:" + targetType + ":" + targetId;
    }

    private static String flushLockKey(String targetType, Long targetId) {
        return "like:flush:lock:" + targetType + ":" + targetId;
    }

    private static String touchSnapshotKey(String targetType, Long targetId) {
        return "like:touch:flush:" + targetType + ":" + targetId;
    }

    private static String member(String targetType, Long targetId) {
        return targetType + ":" + targetId;
    }
}
