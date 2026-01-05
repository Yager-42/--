package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IContentDispatchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 发布分发事件占位实现：记录日志，后续可接入 MQ。
 */
@Slf4j
@Component
public class ContentDispatchPort implements IContentDispatchPort {
    @Override
    public void onPublished(Long postId, Long userId) {
        log.info("Post_Published dispatched. postId={}, userId={}", postId, userId);
    }
}
