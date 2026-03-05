package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Leaf 相关配置。
 *
 * <p>说明：本项目只复用 Leaf 的核心思路（Segment + Snowflake）。其中 Snowflake 的 workerId
 * 由 Zookeeper 分配，确保多实例不冲突。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "leaf")
public class LeafProperties {

    /**
     * Leaf 应用名（用于构造 ZK 路径前缀，避免与其它系统冲突）。
     */
    private String name = "nexus";

    private Snowflake snowflake = new Snowflake();

    @Data
    public static class Snowflake {

        /**
         * 是否启用 snowflake 模式（未启用时不会分配 workerId）。
         */
        private boolean enabled = true;

        /**
         * Zookeeper 连接串，如 127.0.0.1:2181。
         */
        private String zkAddress;

        /**
         * 用于标识实例的端口（写入 ZK 节点数据，不影响 HTTP 端口）。
         */
        private Integer port;
    }
}

