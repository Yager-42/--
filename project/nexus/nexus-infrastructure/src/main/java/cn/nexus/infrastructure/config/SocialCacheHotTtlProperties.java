package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 社交缓存热点延寿配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "social.cache.hot-ttl")
public class SocialCacheHotTtlProperties {

    /**
     * 内容基础信息缓存热点延寿秒数。
     * 小于等于 0 表示关闭延寿。
     */
    private long contentPostSeconds = 300L;

    /**
     * 点赞计数缓存热点延寿秒数。
     * 先预留配置位，当前未启用。
     */
    private long reactionCountSeconds = 0L;

    /**
     * Feed 基础卡片缓存热点延寿秒数。
     * 小于等于 0 表示关闭延寿。
     */
    private long feedCardSeconds = 0L;
}
