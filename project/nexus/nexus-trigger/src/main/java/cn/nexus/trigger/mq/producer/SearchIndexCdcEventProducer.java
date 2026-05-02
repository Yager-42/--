package cn.nexus.trigger.mq.producer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import cn.nexus.trigger.mq.config.SearchIndexCdcMqConfig;
import cn.nexus.types.event.search.PostChangedCdcEvent;
import org.springframework.stereotype.Component;

@Component
public class SearchIndexCdcEventProducer {

    @ReliableMqPublish(exchange = SearchIndexCdcMqConfig.EXCHANGE,
            routingKey = SearchIndexCdcMqConfig.ROUTING_KEY,
            eventId = "#event.eventId",
            payload = "#event")
    public void publish(PostChangedCdcEvent event) {
    }
}
