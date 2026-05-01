package cn.nexus.infrastructure.adapter.auth.port;

import cn.nexus.domain.auth.adapter.port.IAuthThrottlePort;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的认证防刷实现。
 */
@Component
@RequiredArgsConstructor
public class RedisAuthThrottlePort implements IAuthThrottlePort {

    private static final String KEY_LOGIN_FAIL = "auth:login:fail:%s:%s";
    private static final String KEY_LOGIN_LOCK = "auth:login:lock:%s:%s";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${auth.login.fail-threshold:5}")
    private int loginFailThreshold;

    @Value("${auth.login.lock-seconds:900}")
    private long loginLockSeconds;

    public void checkLoginLock(String loginType, String phone) {
        if (exceeded(String.format(KEY_LOGIN_LOCK, loginType, phone), 1)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "登录已锁定，请稍后再试");
        }
    }

    @Override
    public void onLoginSuccess(String loginType, String phone) {
        safeDelete(String.format(KEY_LOGIN_FAIL, loginType, phone));
        safeDelete(String.format(KEY_LOGIN_LOCK, loginType, phone));
    }

    @Override
    public void onLoginFailure(String loginType, String phone) {
        String failKey = String.format(KEY_LOGIN_FAIL, loginType, phone);
        Long current = incr(failKey, Duration.ofSeconds(loginLockSeconds));
        if (current != null && current >= loginFailThreshold) {
            stringRedisTemplate.opsForValue().set(String.format(KEY_LOGIN_LOCK, loginType, phone), "1", Duration.ofSeconds(loginLockSeconds));
        }
    }

    private boolean exceeded(String key, long limit) {
        if (key == null || key.isBlank() || limit <= 0) {
            return false;
        }
        try {
            String raw = stringRedisTemplate.opsForValue().get(key);
            long current = raw == null ? 0L : Long.parseLong(raw.trim());
            return current >= limit;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Long incr(String key, Duration ttl) {
        try {
            Long current = stringRedisTemplate.opsForValue().increment(key);
            if (current != null && current == 1L) {
                stringRedisTemplate.expire(key, ttl);
            }
            return current;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void safeDelete(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception ignored) {
        }
    }
}
