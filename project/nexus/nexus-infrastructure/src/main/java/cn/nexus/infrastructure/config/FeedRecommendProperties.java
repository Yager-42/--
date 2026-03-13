package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐系统配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "feed.recommend")
public class FeedRecommendProperties {

    private String baseUrl = "";

    private int connectTimeoutMs = 200;

    private int readTimeoutMs = 500;

    private int sessionTtlMinutes = 20;

    private int prefetchFactor = 5;

    private int scanFactor = 10;

    private int maxAppendRounds = 3;

    /** 非个性化热门推荐器名称。 */
    private String trendingRecommenderName = "trending";

    /** 非个性化最新推荐器名称。 */
    private String latestRecommenderName = "latest";

    /** item-to-item 推荐器名称。 */
    private String similarRecommenderName = "similar";
}
