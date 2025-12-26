package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationGroupLockPort;
import cn.nexus.domain.social.model.valobj.RelationGroupVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的分组操作锁与幂等存储。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RelationGroupLockPort implements IRelationGroupLockPort {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String KEY_LOCK = "social:group:lock:";
    private static final String KEY_IDEM = "social:group:idem:";

    @Override
    public boolean tryLock(Long userId, String action, long ttlSeconds) {
        if (userId == null) {
            return false;
        }
        String key = KEY_LOCK + userId + ":" + action;
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void unlock(Long userId, String action) {
        if (userId == null) {
            return;
        }
        String key = KEY_LOCK + userId + ":" + action;
        redisTemplate.delete(key);
    }

    @Override
    public RelationGroupVO loadResult(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String json = redisTemplate.opsForValue().get(KEY_IDEM + token);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RelationGroupVO.class);
        } catch (JsonProcessingException e) {
            log.warn("幂等结果反序列化失败 token={}", token, e);
            return null;
        }
    }

    @Override
    public void saveResult(String token, RelationGroupVO vo, long ttlSeconds) {
        if (token == null || token.isBlank() || vo == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_IDEM + token, objectMapper.writeValueAsString(vo), ttlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("幂等结果序列化失败 token={}", token, e);
        }
    }
}
