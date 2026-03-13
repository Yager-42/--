package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全站 latest 配置：推荐系统不可用时的兜底候选源。
 *
 * @author codex
 * @since 2026-01-26
 */
@Data
@Component
@ConfigurationProperties(prefix = "feed.global.latest")
public class FeedGlobalLatestProperties {

    /**
     * 全站 latest 最大保留条数（默认 20000）。
     */
    private int maxSize = 20000;
}

