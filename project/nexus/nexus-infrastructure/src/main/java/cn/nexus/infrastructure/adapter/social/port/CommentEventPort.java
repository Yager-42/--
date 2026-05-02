package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.ICommentEventPort;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import cn.nexus.types.event.interaction.CommentCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 评论事件发布端口实现：使用 RabbitMQ 直接投递。
 *
 * @author codex
 * @since 2026-01-14
 */
@Component
@RequiredArgsConstructor
public class CommentEventPort implements ICommentEventPort {

    private static final String EXCHANGE = "social.interaction";

    private static final String RK_COMMENT_CREATED = "comment.created";

    /**
     * 发布评论创建事件。
     *
     * @param event 评论创建事件。类型：{@link CommentCreatedEvent}
     */
    @Override
    @ReliableMqPublish(exchange = EXCHANGE,
            routingKey = RK_COMMENT_CREATED,
            eventId = "#event.eventId",
            payload = "#event")
    public void publish(CommentCreatedEvent event) {
    }
}
