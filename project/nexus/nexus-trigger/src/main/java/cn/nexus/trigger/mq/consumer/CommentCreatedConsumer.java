package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.CommentCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 评论创建事件消费者：一级评论入热榜。
 *
 * @author codex
 * @since 2026-01-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentCreatedConsumer {

    private final ICommentHotRankRepository hotRankRepository;

    @RabbitListener(queues = InteractionCommentMqConfig.Q_COMMENT_CREATED)
    public void onMessage(CommentCreatedEvent event) {
        if (event == null || event.getPostId() == null || event.getCommentId() == null) {
            return;
        }
        // 热榜只排一级评论
        if (event.getRootId() != null) {
            return;
        }
        hotRankRepository.upsert(event.getPostId(), event.getCommentId(), 0D);
    }
}

