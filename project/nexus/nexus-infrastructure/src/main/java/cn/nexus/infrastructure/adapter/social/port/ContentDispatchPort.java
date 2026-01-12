package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IContentDispatchPort;
import cn.nexus.types.event.PostPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 发布分发事件占位实现：记录日志，后续可接入 MQ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentDispatchPort implements IContentDispatchPort {

    private static final String EXCHANGE = "social.feed";
    private static final String ROUTING_KEY = "post.published";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void onPublished(Long postId, Long userId) {
        PostPublishedEvent event = new PostPublishedEvent();
        event.setPostId(postId);
        event.setAuthorId(userId);
        event.setPublishTimeMs(System.currentTimeMillis());
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);
        log.info("Post_Published dispatched. postId={}, userId={}", postId, userId);
    }
}
