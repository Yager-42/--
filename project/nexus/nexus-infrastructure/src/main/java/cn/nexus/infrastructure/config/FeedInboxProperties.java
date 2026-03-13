package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Feed InboxTimeline 配置。
 *
 * @author codex
 * @since 2026-01-12
 */
@Data
@Component
@ConfigurationProperties(prefix = "feed.inbox")
public class FeedInboxProperties {

    /**
     * InboxTimeline 最大保留条数（默认 1000）。
     */
    private int maxSize = 1000;

    /**
     * InboxTimeline 过期天数（默认 30 天）。
     */
    private int ttlDays = 30;
}

