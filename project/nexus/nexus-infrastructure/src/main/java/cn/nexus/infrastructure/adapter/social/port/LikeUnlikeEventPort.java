package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.ILikeUnlikeEventPort;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Like/Unlike MQ publisher (RabbitMQ).
 */
@Component
@RequiredArgsConstructor
public class LikeUnlikeEventPort implements ILikeUnlikeEventPort {

    private static final String EXCHANGE = "LikeUnlikeTopic";
    private static final String RK_LIKE = "Like";
    private static final String RK_UNLIKE = "Unlike";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publishLike(LikeUnlikePostEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, RK_LIKE, event);
    }

    @Override
    public void publishUnlike(LikeUnlikePostEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, RK_UNLIKE, event);
    }
}
