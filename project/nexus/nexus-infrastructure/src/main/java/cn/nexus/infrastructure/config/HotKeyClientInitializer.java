package cn.nexus.infrastructure.config;

import com.jd.platform.hotkey.client.ClientStarter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class HotKeyClientInitializer {

    private final HotKeyProperties hotKeyProperties;

    @PostConstruct
    public void init() {
        String appName = hotKeyProperties.getAppName();
        String etcdServer = hotKeyProperties.getEtcdServer();
        Long pushPeriodMs = hotKeyProperties.getPushPeriodMs();

        if (appName == null || appName.isBlank() || etcdServer == null || etcdServer.isBlank()) {
            log.warn("hotkey client disabled due to missing config, appName={}, etcdServer={}", appName, etcdServer);
            return;
        }

        try {
            ClientStarter starter = new ClientStarter.Builder()
                    .setAppName(appName)
                    .setEtcdServer(etcdServer)
                    .setPushPeriod(pushPeriodMs == null ? 500L : pushPeriodMs)
                    .build();
            starter.startPipeline();
            log.info("hotkey client started, appName={}, etcdServer={}, pushPeriodMs={}", appName, etcdServer, pushPeriodMs);
        } catch (Exception e) {
            // 外部依赖不可用时不阻断启动：热点治理退化为全量冷 key（仍可用 Redis 跑主链路）。
            log.error("hotkey client init failed, appName={}, etcdServer={}", appName, etcdServer, e);
        }
    }
}

