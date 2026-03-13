package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Feed Outbox 配置。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@Component
@ConfigurationProperties(prefix = "feed.outbox")
public class FeedOutboxProperties {

    /**
     * Outbox 最大保留条数（默认 1000）。
     */
    private int maxSize = 1000;

    /**
     * Outbox 过期天数（默认 30 天）。
     */
    private int ttlDays = 30;
}

