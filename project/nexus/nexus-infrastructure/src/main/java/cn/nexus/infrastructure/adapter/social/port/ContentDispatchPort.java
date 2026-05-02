package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IContentDispatchPort;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostPublishedEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 内容分发事件端口实现：历史直投路径。
 *
 * <p>Active content services publish through {@link ContentEventOutboxPort}; this class is intentionally
 * not a Spring bean and remains only as a legacy adapter reference until the old port is deleted.</p>
 *
 * @author rr
 * @author codex
 * @since 2026-01-05
 */
@Slf4j
@RequiredArgsConstructor
public class ContentDispatchPort implements IContentDispatchPort {

    private static final String EXCHANGE = "social.feed";
    private static final String ROUTING_KEY = "post.published";
    private static final String ROUTING_KEY_UPDATED = "post.updated";
    private static final String ROUTING_KEY_DELETED = "post.deleted";

    private final RabbitTemplate rabbitTemplate;

    /**
     * 执行 onPublished 逻辑。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     * @param userId 当前用户 ID。类型：{@link Long}
     */
    @Override
    public void onPublished(Long postId, Long userId) {
        PostPublishedEvent event = new PostPublishedEvent();
        event.setPostId(postId);
        event.setAuthorId(userId);
        event.setPublishTimeMs(System.currentTimeMillis());
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);
        log.info("Post_Published dispatched. postId={}, userId={}", postId, userId);
    }

    /**
     * 执行 onUpdated 逻辑。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     * @param operatorId operatorId 参数。类型：{@link Long}
     */
    @Override
    public void onUpdated(Long postId, Long operatorId) {
        PostUpdatedEvent event = new PostUpdatedEvent();
        event.setPostId(postId);
        event.setOperatorId(operatorId);
        event.setTsMs(System.currentTimeMillis());
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_UPDATED, event);
        log.info("Post_Updated dispatched. postId={}, operatorId={}", postId, operatorId);
    }

    /**
     * 执行 onDeleted 逻辑。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     * @param operatorId operatorId 参数。类型：{@link Long}
     */
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
