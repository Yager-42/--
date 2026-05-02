package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地热点 key 检测配置。
 *
 * @author codex
 * @since 2026-05-02
 */
@Data
@Component
@ConfigurationProperties(prefix = "hotkey")
public class HotKeyProperties {

    /**
     * 是否启用本地热点 key 检测。
     */
    private boolean enabled = true;

    /**
     * 滑动窗口总时长（秒）。
     */
    private int windowSeconds = 60;

    /**
     * 单个计数分片时长（秒）。
     */
    private int segmentSeconds = 10;

    /**
     * 低热度阈值。
     */
    private int levelLow = 50;

    /**
     * 中热度阈值。
     */
    private int levelMedium = 200;

    /**
     * 高热度阈值。
     */
    private int levelHigh = 500;
}
