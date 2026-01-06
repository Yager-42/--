package cn.nexus.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
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
        SingleServerConfig single = config.useSingleServer()
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase());
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
            single.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }
}
