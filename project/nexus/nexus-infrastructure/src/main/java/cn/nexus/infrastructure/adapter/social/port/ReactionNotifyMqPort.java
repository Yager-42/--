package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IReactionNotifyMqPort;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReactionNotifyMqPort implements IReactionNotifyMqPort {

    private static final String EXCHANGE = "social.interaction";
    private static final String ROUTING_KEY = "interaction.notify";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(InteractionNotifyEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);
    }
}
