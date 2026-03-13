package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.LikeUnlikeMqConfig;
import cn.nexus.trigger.mq.consumer.strategy.PostLikeCountAggregateStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ConsumerGroup B: aggregate (1000/1s) and send count snapshots to DB topic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostLikeCountAggregateConsumer {

    private final PostLikeCountAggregateStrategy strategy;

    @RabbitListener(queues = LikeUnlikeMqConfig.QUEUE_COUNT, containerFactory = "likeUnlikeBatchListenerContainerFactory")
    public void onMessages(List<Message> messages) {
        strategy.handle(messages);
    }
}
