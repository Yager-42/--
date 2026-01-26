package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐系统配置（Phase 3）。
 *
 * <p>注意：这些配置名在文档 11.11.7 已定死，避免实现者随意发明。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Data
@Component
@ConfigurationProperties(prefix = "feed.recommend")
public class FeedRecommendProperties {

    /**
     * Gorse Base URL（示例：http://127.0.0.1:8087）。为空表示不启用 gorse（读侧自动降级 latest）。
     */
    private String baseUrl = "";

    /**
     * HTTP 连接超时（毫秒），默认 200ms。
     */
    private int connectTimeoutMs = 200;

    /**
     * HTTP 读取超时（毫秒），默认 500ms。
     */
    private int readTimeoutMs = 500;

    /**
     * session TTL（分钟），默认 20 分钟。
     */
    private int sessionTtlMinutes = 20;

    /**
     * 追加候选批量系数：appendBatch = limit * prefetchFactor，默认 5。
     */
    private int prefetchFactor = 5;

    /**
     * 扫描预算系数：scanBudget = limit * scanFactor，默认 10。
     */
    private int scanFactor = 10;

    /**
     * 最大追加轮数，默认 3（避免无限拉取/死循环）。
     */
    private int maxAppendRounds = 3;
}

