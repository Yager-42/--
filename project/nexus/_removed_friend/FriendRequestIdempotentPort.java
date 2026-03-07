package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IFriendRequestIdempotentPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis 的好友请求幂等占位，实现 setIfAbsent 防止重复提交。
 */
@Component
@RequiredArgsConstructor
public class FriendRequestIdempotentPort implements IFriendRequestIdempotentPort {

    private static final String KEY_PREFIX = "social:friend:req:idem:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean acquire(String key, long ttlSeconds) {
        if (key == null || key.isBlank()) {
            return false;
        }
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + key, "1", Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(ok);
    }
}
