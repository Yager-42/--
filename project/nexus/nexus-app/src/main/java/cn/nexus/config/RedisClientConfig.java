package cn.nexus.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置。
 *
 * 提供单节点 RedissonClient，复用 Spring Redis 配置，供领域层获取分布式锁。
 *
 * @author codex
 * @since 2025-01-11
 */
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisClientConfig {

    /**
     * 基于 Spring Redis 配置创建 RedissonClient。
     *
     * @param redisProperties {@link RedisProperties} Spring Redis 基础配置
     * @return {@link RedissonClient} Redisson 客户端
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        String scheme = redisProperties.getSsl().isEnabled() ? "rediss://" : "redis://";
        String address = scheme + redisProperties.getHost() + ":" + redisProperties.getPort();

        SingleServerConfig single = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisProperties.getDatabase());

        String username = redisProperties.getUsername();
        if (username != null && !username.isEmpty()) {
            single.setUsername(username);
        }

        String password = redisProperties.getPassword();
        if (password != null && !password.isEmpty()) {
            single.setPassword(password);
        }

        if (redisProperties.getTimeout() != null) {
            single.setTimeout((int) redisProperties.getTimeout().toMillis());
        }
        return Redisson.create(config);
    }
}
