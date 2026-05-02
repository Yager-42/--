package cn.nexus.trigger.mq.producer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.event.FeedFanoutTask;
import org.springframework.stereotype.Component;

@Component
public class FeedFanoutTaskProducer {

    @ReliableMqPublish(exchange = FeedFanoutConfig.EXCHANGE,
            routingKey = FeedFanoutConfig.TASK_ROUTING_KEY,
            eventId = "#task.eventId",
            payload = "#task")
    public void publish(FeedFanoutTask task) {
    }
}
