package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 社交领域 ID 与时间提供器。
 *
 * @author codex
 * @since 2026-01-09
 */
@Component
@RequiredArgsConstructor
public class SocialIdPort implements ISocialIdPort {

    private final RedisSnowflakeIdGenerator idGenerator;

    /**
     * 生成全局唯一 ID。
     *
     * @return {@code Long} 全局唯一 ID
     */
    @Override
    public Long nextId() {
        return idGenerator.nextId();
    }

    /**
     * 返回当前时间戳。
     *
     * @return {@code Long} 当前毫秒时间戳
     */
    @Override
    public Long now() {
        return System.currentTimeMillis();
    }
}
