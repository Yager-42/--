package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大 V 聚合池配置（兜底优化）。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@Component
@ConfigurationProperties(prefix = "feed.bigv.pool")
public class FeedBigVPoolProperties {

    /**
     * 是否启用聚合池（默认 false，按需开启）。
     */
    private boolean enabled = false;

    /**
     * 分桶数量（默认 4）。
     */
    private int buckets = 4;

    /**
     * 每个 bucket 最大保留条数（默认 500000）。
     */
    private int maxSizePerBucket = 500000;

    /**
     * 聚合池过期天数（默认 7 天）。
     */
    private int ttlDays = 7;

    /**
     * 读侧从池里拉取的放大系数（默认 30）：抵消“池里有很多你不关注的大 V”造成的无效命中。
     */
    private int fetchFactor = 30;
}

