package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IPostLikeCachePort;
import cn.nexus.domain.social.model.valobj.like.PostLikeApplyResultVO;
import cn.nexus.domain.social.model.valobj.like.PostLikeApplyStatusEnumVO;
import cn.nexus.domain.social.model.valobj.like.PostLikeCacheStateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Post-like cache: Bloom (bitmap) + recent likes ZSet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostLikeCachePort implements IPostLikeCachePort {

    private static final String KEY_BLOOM_PREFIX = "bloom:post:likes:";
    private static final String KEY_ZSET_PREFIX = "user:post:likes:";
    private static final String KEY_PENDING_LIKE_PREFIX = "pending:post:like:";
    private static final String KEY_PENDING_UNLIKE_PREFIX = "pending:post:unlike:";

    private static final String KEY_CNT_PREFIX = "interact:reaction:cnt:";

    /**
     * 派生计数：用户收到的点赞数（targetType=USER, reactionType=LIKE）。
     */
    private static final String KEY_CREATOR_CNT_PREFIX = KEY_CNT_PREFIX;

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${like.bloom.size:262144}")
    private int bloomSize;

    @Value("${like.bloom.ttl-sec:2592000}")
    private int bloomTtlSec;

    @Value("${like.zset.max-size:100}")
    private int zsetMaxSize;

    @Value("${like.zset.ttl-sec:86400}")
    private int zsetTtlSec;

    @Value("${like.pending.ttl-sec:600}")
    private int pendingTtlSec;

    @Override
    public PostLikeApplyResultVO tryLike(Long userId, Long postId, long nowMs) {
        if (userId == null || postId == null) {
            return PostLikeApplyResultVO.builder().status(PostLikeApplyStatusEnumVO.ALREADY.getCode()).delta(0).currentCount(0L).build();
        }
        List<?> res = stringRedisTemplate.execute(
                LIKE_TRY_SCRIPT,
                List.of(bloomKey(userId), zsetKey(userId), pendingLikeKey(userId, postId), cntKey(postId)),
                String.valueOf(postId),
                String.valueOf(nowMs),
                String.valueOf(Math.max(1, zsetMaxSize)),
                String.valueOf(Math.max(1, zsetTtlSec)),
                String.valueOf(Math.max(1024, bloomSize)),
                String.valueOf(Math.max(1, pendingTtlSec)),
                String.valueOf(Math.max(1, bloomTtlSec))
        );
        return toApplyResult(res);
    }

    @Override
    public PostLikeApplyResultVO forceLike(Long userId, Long postId, long nowMs) {
        if (userId == null || postId == null) {
            return PostLikeApplyResultVO.builder().status(PostLikeApplyStatusEnumVO.ALREADY.getCode()).delta(0).currentCount(0L).build();
        }
        List<?> res = stringRedisTemplate.execute(
                LIKE_FORCE_SCRIPT,
                List.of(bloomKey(userId), zsetKey(userId), pendingLikeKey(userId, postId), cntKey(postId)),
                String.valueOf(postId),
                String.valueOf(nowMs),
                String.valueOf(Math.max(1, zsetMaxSize)),
                String.valueOf(Math.max(1, zsetTtlSec)),
                String.valueOf(Math.max(1024, bloomSize)),
                String.valueOf(Math.max(1, pendingTtlSec)),
                String.valueOf(Math.max(1, bloomTtlSec))
        );
        return toApplyResult(res);
    }

    @Override
    public PostLikeApplyResultVO tryUnlike(Long userId, Long postId, long nowMs) {
        if (userId == null || postId == null) {
            return PostLikeApplyResultVO.builder().status(PostLikeApplyStatusEnumVO.ALREADY.getCode()).delta(0).currentCount(0L).build();
        }
        List<?> res = stringRedisTemplate.execute(
                UNLIKE_TRY_SCRIPT,
                List.of(zsetKey(userId), pendingLikeKey(userId, postId), pendingUnlikeKey(userId, postId), cntKey(postId)),
                String.valueOf(postId),
                String.valueOf(nowMs),
                String.valueOf(Math.max(1, pendingTtlSec)),
                String.valueOf(Math.max(1, zsetTtlSec))
        );
        return toApplyResult(res);
    }

    @Override
    public PostLikeApplyResultVO forceUnlike(Long userId, Long postId, long nowMs) {
        if (userId == null || postId == null) {
            return PostLikeApplyResultVO.builder().status(PostLikeApplyStatusEnumVO.ALREADY.getCode()).delta(0).currentCount(0L).build();
        }
        List<?> res = stringRedisTemplate.execute(
                UNLIKE_FORCE_SCRIPT,
                List.of(zsetKey(userId), pendingLikeKey(userId, postId), pendingUnlikeKey(userId, postId), cntKey(postId)),
                String.valueOf(postId),
                String.valueOf(nowMs),
                String.valueOf(Math.max(1, pendingTtlSec)),
                String.valueOf(Math.max(1, zsetTtlSec))
        );
        return toApplyResult(res);
    }

    @Override
    public PostLikeCacheStateVO cacheState(Long userId, Long postId) {
        if (userId == null || postId == null) {
            return PostLikeCacheStateVO.builder().liked(null).currentCount(0L).build();
        }
        boolean pendingUnlike = exists(pendingUnlikeKey(userId, postId));
        if (pendingUnlike) {
            return PostLikeCacheStateVO.builder().liked(false).currentCount(getCnt(postId)).build();
        }

        boolean pendingLike = exists(pendingLikeKey(userId, postId));
        if (pendingLike) {
            return PostLikeCacheStateVO.builder().liked(true).currentCount(getCnt(postId)).build();
        }

        boolean inZset = false;
        try {
            Double score = stringRedisTemplate.opsForZSet().score(zsetKey(userId), String.valueOf(postId));
            inZset = score != null;
        } catch (Exception e) {
            log.warn("zset score failed, userId={}, postId={}", userId, postId, e);
        }
        if (inZset) {
            return PostLikeCacheStateVO.builder().liked(true).currentCount(getCnt(postId)).build();
        }
        return PostLikeCacheStateVO.builder().liked(null).currentCount(getCnt(postId)).build();
    }

    @Override
    public long applyCreatorLikeDelta(Long creatorId, int delta) {
        if (creatorId == null) {
            return 0L;
        }
        if (delta == 0) {
            return getRawLong(creatorCntKey(creatorId));
        }
        try {
            Long out = stringRedisTemplate.execute(
                    CREATOR_CNT_APPLY_SCRIPT,
                    List.of(creatorCntKey(creatorId)),
                    String.valueOf(delta)
            );
            return out == null ? 0L : Math.max(0L, out);
        } catch (Exception e) {
            log.warn("apply creator like delta failed, creatorId={}, delta={}", creatorId, delta, e);
            return getRawLong(creatorCntKey(creatorId));
        }
    }

    private boolean exists(String key) {
        try {
            Boolean exists = stringRedisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception ignored) {
            return false;
        }
    }

    private long getCnt(Long postId) {
        if (postId == null) {
            return 0L;
        }
        try {
            String raw = stringRedisTemplate.opsForValue().get(cntKey(postId));
            long v = raw == null ? 0L : Long.parseLong(raw.trim());
            return Math.max(0L, v);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long getRawLong(String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        try {
            String raw = stringRedisTemplate.opsForValue().get(key);
            long v = raw == null ? 0L : Long.parseLong(raw.trim());
            return Math.max(0L, v);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private PostLikeApplyResultVO toApplyResult(List<?> res) {
        if (res == null || res.size() < 3) {
            return PostLikeApplyResultVO.builder().status(PostLikeApplyStatusEnumVO.NEED_DB_CHECK.getCode()).delta(0).currentCount(0L).build();
        }
        Integer status = toInt(res.get(0));
        Integer delta = toInt(res.get(1));
        Long cnt = toLong(res.get(2));
        if (status == null) {
            status = PostLikeApplyStatusEnumVO.NEED_DB_CHECK.getCode();
        }
        if (delta == null) {
            delta = 0;
        }
        if (cnt == null) {
            cnt = 0L;
        }
        return PostLikeApplyResultVO.builder()
                .status(status)
                .delta(delta)
                .currentCount(Math.max(0L, cnt))
                .build();
    }

    private Integer toInt(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String bloomKey(Long userId) {
        return KEY_BLOOM_PREFIX + userId;
    }

    private String zsetKey(Long userId) {
        return KEY_ZSET_PREFIX + userId;
    }

    private String pendingLikeKey(Long userId, Long postId) {
        return KEY_PENDING_LIKE_PREFIX + userId + ":" + postId;
    }

    private String pendingUnlikeKey(Long userId, Long postId) {
        return KEY_PENDING_UNLIKE_PREFIX + userId + ":" + postId;
    }

    private String cntKey(Long postId) {
        return KEY_CNT_PREFIX + "{POST:" + postId + ":LIKE}";
    }

    private String creatorCntKey(Long creatorId) {
        return KEY_CREATOR_CNT_PREFIX + "{USER:" + creatorId + ":LIKE}";
    }

    private static <T> DefaultRedisScript<T> script(String text, Class<T> resultType) {
        DefaultRedisScript<T> s = new DefaultRedisScript<>();
        s.setResultType(resultType);
        s.setScriptText(text);
        return s;
    }

    private static final String LUA_LIKE_TRY = ""
            + "local bloomKey = KEYS[1]\n"
            + "local zsetKey = KEYS[2]\n"
            + "local pendingKey = KEYS[3]\n"
            + "local cntKey = KEYS[4]\n"
            + "\n"
            + "local postId = ARGV[1]\n"
            + "local nowMs = tonumber(ARGV[2])\n"
            + "local maxSize = tonumber(ARGV[3])\n"
            + "local zsetTtl = tonumber(ARGV[4])\n"
            + "local bloomSize = tonumber(ARGV[5])\n"
            + "local pendingTtl = tonumber(ARGV[6])\n"
            + "local bloomTtl = tonumber(ARGV[7])\n"
            + "\n"
            + "local cntRaw = redis.call('GET', cntKey)\n"
            + "local cnt = tonumber(cntRaw or '0')\n"
            + "if (not cnt) or cnt < 0 then cnt = 0 end\n"
            + "\n"
            + "if redis.call('ZSCORE', zsetKey, postId) then\n"
            + "  return {0, 0, cnt}\n"
            + "end\n"
            + "if redis.call('EXISTS', pendingKey) == 1 then\n"
            + "  return {0, 0, cnt}\n"
            + "end\n"
            + "\n"
            + "local h = redis.sha1hex(postId)\n"
            + "local n1 = tonumber(string.sub(h, 1, 8), 16) or 0\n"
            + "local n2 = tonumber(string.sub(h, 9, 16), 16) or 0\n"
            + "local n3 = tonumber(string.sub(h, 17, 24), 16) or 0\n"
            + "local p1 = n1 % bloomSize\n"
            + "local p2 = n2 % bloomSize\n"
            + "local p3 = n3 % bloomSize\n"
            + "\n"
            + "local b1 = redis.call('GETBIT', bloomKey, p1)\n"
            + "local b2 = redis.call('GETBIT', bloomKey, p2)\n"
            + "local b3 = redis.call('GETBIT', bloomKey, p3)\n"
            + "if b1 == 1 and b2 == 1 and b3 == 1 then\n"
            + "  return {2, 0, cnt}\n"
            + "end\n"
            + "\n"
            + "redis.call('SETBIT', bloomKey, p1, 1)\n"
            + "redis.call('SETBIT', bloomKey, p2, 1)\n"
            + "redis.call('SETBIT', bloomKey, p3, 1)\n"
            + "redis.call('EXPIRE', bloomKey, bloomTtl)\n"
            + "\n"
            + "redis.call('ZADD', zsetKey, nowMs, postId)\n"
            + "local size = redis.call('ZCARD', zsetKey)\n"
            + "if size > maxSize then\n"
            + "  local endIdx = size - maxSize - 1\n"
            + "  if endIdx >= 0 then\n"
            + "    redis.call('ZREMRANGEBYRANK', zsetKey, 0, endIdx)\n"
            + "  end\n"
            + "end\n"
            + "redis.call('EXPIRE', zsetKey, zsetTtl)\n"
            + "\n"
            + "redis.call('SET', pendingKey, '1', 'EX', pendingTtl)\n"
            + "\n"
            + "if not cntRaw then\n"
            + "  redis.call('SET', cntKey, '0')\n"
            + "end\n"
            + "local newCnt = tonumber(redis.call('INCR', cntKey) or '0')\n"
            + "if not newCnt or newCnt < 0 then\n"
            + "  newCnt = 0\n"
            + "  redis.call('SET', cntKey, '0')\n"
            + "end\n"
            + "return {1, 1, newCnt}\n";

    private static final String LUA_LIKE_FORCE = ""
            + "local bloomKey = KEYS[1]\n"
            + "local zsetKey = KEYS[2]\n"
            + "local pendingKey = KEYS[3]\n"
            + "local cntKey = KEYS[4]\n"
            + "\n"
            + "local postId = ARGV[1]\n"
            + "local nowMs = tonumber(ARGV[2])\n"
            + "local maxSize = tonumber(ARGV[3])\n"
            + "local zsetTtl = tonumber(ARGV[4])\n"
            + "local bloomSize = tonumber(ARGV[5])\n"
            + "local pendingTtl = tonumber(ARGV[6])\n"
            + "local bloomTtl = tonumber(ARGV[7])\n"
            + "\n"
            + "local cntRaw = redis.call('GET', cntKey)\n"
            + "local cnt = tonumber(cntRaw or '0')\n"
            + "if (not cnt) or cnt < 0 then cnt = 0 end\n"
            + "\n"
            + "if redis.call('ZSCORE', zsetKey, postId) then\n"
            + "  return {0, 0, cnt}\n"
            + "end\n"
            + "local ok = redis.call('SET', pendingKey, '1', 'NX', 'EX', pendingTtl)\n"
            + "if not ok then\n"
            + "  return {0, 0, cnt}\n"
            + "end\n"
            + "\n"
            + "local h = redis.sha1hex(postId)\n"
            + "local n1 = tonumber(string.sub(h, 1, 8), 16) or 0\n"
            + "local n2 = tonumber(string.sub(h, 9, 16), 16) or 0\n"
            + "local n3 = tonumber(string.sub(h, 17, 24), 16) or 0\n"
            + "local p1 = n1 % bloomSize\n"
            + "local p2 = n2 % bloomSize\n"
            + "local p3 = n3 % bloomSize\n"
            + "redis.call('SETBIT', bloomKey, p1, 1)\n"
            + "redis.call('SETBIT', bloomKey, p2, 1)\n"
            + "redis.call('SETBIT', bloomKey, p3, 1)\n"
            + "redis.call('EXPIRE', bloomKey, bloomTtl)\n"
            + "\n"
            + "redis.call('ZADD', zsetKey, nowMs, postId)\n"
            + "local size = redis.call('ZCARD', zsetKey)\n"
            + "if size > maxSize then\n"
            + "  local endIdx = size - maxSize - 1\n"
            + "  if endIdx >= 0 then\n"
            + "    redis.call('ZREMRANGEBYRANK', zsetKey, 0, endIdx)\n"
            + "  end\n"
            + "end\n"
            + "redis.call('EXPIRE', zsetKey, zsetTtl)\n"
            + "\n"
            + "if not cntRaw then\n"
            + "  redis.call('SET', cntKey, '0')\n"
            + "end\n"
            + "local newCnt = tonumber(redis.call('INCR', cntKey) or '0')\n"
            + "if not newCnt or newCnt < 0 then\n"
            + "  newCnt = 0\n"
            + "  redis.call('SET', cntKey, '0')\n"
            + "end\n"
            + "return {1, 1, newCnt}\n";

    private static final String LUA_UNLIKE_TRY = ""
            + "local zsetKey = KEYS[1]\n"
            + "local pendingLikeKey = KEYS[2]\n"
            + "local pendingUnlikeKey = KEYS[3]\n"
            + "local cntKey = KEYS[4]\n"
            + "\n"
            + "local postId = ARGV[1]\n"
            + "local pendingTtl = tonumber(ARGV[3])\n"
            + "local zsetTtl = tonumber(ARGV[4])\n"
            + "\n"
            + "local cntRaw = redis.call('GET', cntKey)\n"
            + "local cnt = tonumber(cntRaw or '0')\n"
            + "if (not cnt) or cnt < 0 then cnt = 0 end\n"
            + "\n"
            + "if redis.call('EXISTS', pendingUnlikeKey) == 1 then\n"
            + "  return {0, 0, cnt}\n"
            + "end\n"
            + "\n"
            + "local inZ = redis.call('ZSCORE', zsetKey, postId)\n"
            + "local inPendingLike = redis.call('EXISTS', pendingLikeKey)\n"
            + "if inZ or inPendingLike == 1 then\n"
            + "  redis.call('ZREM', zsetKey, postId)\n"
            + "  redis.call('EXPIRE', zsetKey, zsetTtl)\n"
            + "  redis.call('DEL', pendingLikeKey)\n"
            + "  redis.call('SET', pendingUnlikeKey, '1', 'NX', 'EX', pendingTtl)\n"
            + "  if not cntRaw then redis.call('SET', cntKey, '0') end\n"
            + "  local newCnt = tonumber(redis.call('DECR', cntKey) or '0')\n"
            + "  if not newCnt or newCnt < 0 then\n"
            + "    newCnt = 0\n"
            + "    redis.call('SET', cntKey, '0')\n"
            + "  end\n"
            + "  return {1, -1, newCnt}\n"
            + "end\n"
            + "\n"
            + "return {2, 0, cnt}\n";

    private static final String LUA_UNLIKE_FORCE = ""
            + "local zsetKey = KEYS[1]\n"
            + "local pendingLikeKey = KEYS[2]\n"
            + "local pendingUnlikeKey = KEYS[3]\n"
            + "local cntKey = KEYS[4]\n"
            + "\n"
            + "local postId = ARGV[1]\n"
            + "local pendingTtl = tonumber(ARGV[3])\n"
            + "local zsetTtl = tonumber(ARGV[4])\n"
            + "\n"
            + "local cntRaw = redis.call('GET', cntKey)\n"
            + "local cnt = tonumber(cntRaw or '0')\n"
            + "if (not cnt) or cnt < 0 then cnt = 0 end\n"
            + "\n"
            + "if redis.call('EXISTS', pendingUnlikeKey) == 1 then\n"
            + "  return {0, 0, cnt}\n"
            + "end\n"
            + "local ok = redis.call('SET', pendingUnlikeKey, '1', 'NX', 'EX', pendingTtl)\n"
            + "if not ok then\n"
            + "  return {0, 0, cnt}\n"
            + "end\n"
            + "\n"
            + "redis.call('ZREM', zsetKey, postId)\n"
            + "redis.call('EXPIRE', zsetKey, zsetTtl)\n"
            + "redis.call('DEL', pendingLikeKey)\n"
            + "\n"
            + "if not cntRaw then redis.call('SET', cntKey, '0') end\n"
            + "local newCnt = tonumber(redis.call('DECR', cntKey) or '0')\n"
            + "if not newCnt or newCnt < 0 then\n"
            + "  newCnt = 0\n"
            + "  redis.call('SET', cntKey, '0')\n"
            + "end\n"
            + "return {1, -1, newCnt}\n";

    private static final DefaultRedisScript<List> LIKE_TRY_SCRIPT = script(LUA_LIKE_TRY, List.class);
    private static final DefaultRedisScript<List> LIKE_FORCE_SCRIPT = script(LUA_LIKE_FORCE, List.class);
    private static final DefaultRedisScript<List> UNLIKE_TRY_SCRIPT = script(LUA_UNLIKE_TRY, List.class);
    private static final DefaultRedisScript<List> UNLIKE_FORCE_SCRIPT = script(LUA_UNLIKE_FORCE, List.class);

    private static final String LUA_CREATOR_CNT_APPLY = ""
            + "local key = KEYS[1]\n"
            + "local delta = tonumber(ARGV[1])\n"
            + "if not delta then delta = 0 end\n"
            + "local v = tonumber(redis.call('INCRBY', key, delta) or '0')\n"
            + "if (not v) or v < 0 then\n"
            + "  redis.call('SET', key, '0')\n"
            + "  v = 0\n"
            + "end\n"
            + "return v\n";

    private static final DefaultRedisScript<Long> CREATOR_CNT_APPLY_SCRIPT = script(LUA_CREATOR_CNT_APPLY, Long.class);
}
