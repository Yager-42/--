package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IPostLikeCachePort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Derived post-like counters stored in Redis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostLikeCachePort implements IPostLikeCachePort {

    private static final String KEY_CNT_PREFIX = "interact:reaction:cnt:";
    private static final String KEY_CREATOR_CNT_PREFIX = KEY_CNT_PREFIX;

    private final StringRedisTemplate stringRedisTemplate;

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

    private String creatorCntKey(Long creatorId) {
        return KEY_CREATOR_CNT_PREFIX + "{USER:" + creatorId + ":LIKE}";
    }

    private static <T> DefaultRedisScript<T> script(String text, Class<T> resultType) {
        DefaultRedisScript<T> s = new DefaultRedisScript<>();
        s.setResultType(resultType);
        s.setScriptText(text);
        return s;
    }

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
