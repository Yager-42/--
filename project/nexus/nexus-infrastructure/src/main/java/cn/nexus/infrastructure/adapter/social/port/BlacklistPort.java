package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IBlacklistPort;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单黑名单实现（内存 Set，可替换为外部服务）。
 */
@Component
public class BlacklistPort implements IBlacklistPort {

    private final Set<String> blocked = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isBlocked(Long sourceId, Long targetId) {
        return blocked.contains(key(sourceId, targetId));
    }

    private String key(Long sourceId, Long targetId) {
        return sourceId + "-" + targetId;
    }
}
