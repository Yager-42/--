package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IReactionCommentLikeChangedMqPort;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReactionCommentLikeChangedMqPort implements IReactionCommentLikeChangedMqPort {

    private static final String EXCHANGE = "social.interaction";
    private static final String ROUTING_KEY = "comment.like.changed";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(CommentLikeChangedEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);
    }
}
