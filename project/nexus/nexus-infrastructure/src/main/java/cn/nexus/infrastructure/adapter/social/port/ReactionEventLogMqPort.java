package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IReactionEventLogMqPort;
import cn.nexus.domain.social.model.valobj.ReactionEventLogRecordVO;
import cn.nexus.types.event.interaction.ReactionEventLogMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReactionEventLogMqPort implements IReactionEventLogMqPort {

    private static final String EXCHANGE = "social.interaction";
    private static final String ROUTING_KEY = "reaction.event.log";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(ReactionEventLogRecordVO event) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()) {
            return;
        }
        ReactionEventLogMessage message = new ReactionEventLogMessage();
        message.setEventId(event.getEventId());
        message.setTargetType(event.getTargetType());
        message.setTargetId(event.getTargetId());
        message.setReactionType(event.getReactionType());
        message.setUserId(event.getUserId());
        message.setDesiredState(event.getDesiredState());
        message.setDelta(event.getDelta());
        message.setEventTime(event.getEventTime());
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, message);
    }
}
