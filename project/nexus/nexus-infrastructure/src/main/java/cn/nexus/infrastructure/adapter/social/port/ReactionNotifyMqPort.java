package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IReactionNotifyMqPort;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import org.springframework.stereotype.Component;

@Component
public class ReactionNotifyMqPort implements IReactionNotifyMqPort {

    private static final String EXCHANGE = "social.interaction";
    private static final String ROUTING_KEY = "interaction.notify";

    @Override
    @ReliableMqPublish(exchange = EXCHANGE,
            routingKey = ROUTING_KEY,
            eventId = "#event.eventId",
            payload = "#event")
    public void publish(InteractionNotifyEvent event) {
    }
}
