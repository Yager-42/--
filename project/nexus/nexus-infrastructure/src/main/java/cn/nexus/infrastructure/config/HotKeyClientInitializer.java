package cn.nexus.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 京东 HotKey 客户端初始化：必须在应用启动时完成。
 *
 * <p>注意：外部系统（etcd + worker + dashboard）不在本仓库内交付；这里只负责 client 初始化。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hotkey.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class HotKeyClientInitializer {

    private final HotKeyStoreBridge hotKeyStoreBridge;

    @PostConstruct
    public void init() {
        try {
            hotKeyStoreBridge.startClient();
        } catch (Exception e) {
            // 外部依赖不可用时不阻断启动：热点治理退化为全量冷 key（仍可用 Redis 跑主链路）。
            log.error("hotkey client init failed", e);
        }
    }
}
