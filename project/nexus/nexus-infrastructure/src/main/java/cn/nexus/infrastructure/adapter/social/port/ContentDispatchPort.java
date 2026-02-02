package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IContentDispatchPort;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostPublishedEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 内容分发事件端口实现：使用 RabbitMQ 直接投递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentDispatchPort implements IContentDispatchPort {

    private static final String EXCHANGE = "social.feed";
    private static final String ROUTING_KEY = "post.published";
    private static final String ROUTING_KEY_UPDATED = "post.updated";
    private static final String ROUTING_KEY_DELETED = "post.deleted";

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

    @Override
    public void onUpdated(Long postId, Long operatorId) {
        PostUpdatedEvent event = new PostUpdatedEvent();
        event.setPostId(postId);
        event.setOperatorId(operatorId);
        event.setTsMs(System.currentTimeMillis());
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_UPDATED, event);
        log.info("Post_Updated dispatched. postId={}, operatorId={}", postId, operatorId);
    }

    @Override
    public void onDeleted(Long postId, Long operatorId) {
        PostDeletedEvent event = new PostDeletedEvent();
        event.setPostId(postId);
        event.setOperatorId(operatorId);
        event.setTsMs(System.currentTimeMillis());
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_DELETED, event);
        log.info("Post_Deleted dispatched. postId={}, operatorId={}", postId, operatorId);
    }
}
