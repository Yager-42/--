package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IReactionRecommendFeedbackMqPort;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import org.springframework.stereotype.Component;

@Component
public class ReactionRecommendFeedbackMqPort implements IReactionRecommendFeedbackMqPort {

    private static final String EXCHANGE = "social.recommend";
    private static final String ROUTING_KEY = "recommend.feedback";

    @Override
    @ReliableMqPublish(exchange = EXCHANGE,
            routingKey = ROUTING_KEY,
            eventId = "#event.eventId",
            payload = "#event")
    public void publish(RecommendFeedbackEvent event) {
    }
}
