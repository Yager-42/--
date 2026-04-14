package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IReactionRecommendFeedbackMqPort;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReactionRecommendFeedbackMqPort implements IReactionRecommendFeedbackMqPort {

    private static final String EXCHANGE = "social.recommend";
    private static final String ROUTING_KEY = "recommend.feedback";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(RecommendFeedbackEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);
    }
}
