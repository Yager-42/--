package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.ICommentEventPort;
import cn.nexus.types.event.interaction.CommentCreatedEvent;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import cn.nexus.types.event.interaction.RootReplyCountChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private static final String RK_REPLY_COUNT_CHANGED = "comment.reply_count.changed";
    private static final String RK_COMMENT_LIKE_CHANGED = "comment.like.changed";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(CommentCreatedEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, RK_COMMENT_CREATED, event);
    }

    @Override
    public void publish(RootReplyCountChangedEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, RK_REPLY_COUNT_CHANGED, event);
    }

    @Override
    public void publish(CommentLikeChangedEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, RK_COMMENT_LIKE_CHANGED, event);
    }
}

