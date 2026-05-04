package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Feed AuthorTimeline 配置。
 *
 * @author codex
 * @since 2026-05-04
 */
@Data
@Component
@ConfigurationProperties(prefix = "feed.timeline")
public class FeedAuthorTimelineProperties {

    /**
     * AuthorTimeline 最大保留条数（默认 1000）。
     */
    private int maxSize = 1000;

    /**
     * AuthorTimeline 过期天数（默认 30 天）。
     */
    private int ttlDays = 30;
}
