package cn.nexus.domain.social.adapter.port;

import cn.nexus.types.event.interaction.CommentCreatedEvent;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import cn.nexus.types.event.interaction.RootReplyCountChangedEvent;

/**
 * 评论事件发布端口：domain 只调用端口，不依赖 RabbitTemplate。
 *
 * @author codex
 * @since 2026-01-14
 */
public interface ICommentEventPort {
    void publish(CommentCreatedEvent event);

    void publish(RootReplyCountChangedEvent event);

    void publish(CommentLikeChangedEvent event);
}

