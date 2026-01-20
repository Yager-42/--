package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 京东 HotKey 配置。
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
@Component
@ConfigurationProperties(prefix = "hotkey")
public class HotKeyProperties {

    /**
     * 应用名：需要与 hotkey dashboard 的 appName / workerPath 保持一致。
     */
    private String appName;

    /**
     * etcd 连接串，例如 http://127.0.0.1:2379
     */
    private String etcdServer;

    /**
     * client pushPeriod（毫秒），默认 500ms。
     */
    private Long pushPeriodMs = 500L;
}

