package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.CountPostLikeMqConfig;
import cn.nexus.trigger.mq.consumer.strategy.PostLikeCount2DbStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ConsumerGroup C: write count snapshots to DB (rate limited).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostLikeCount2DbConsumer {

    private final PostLikeCount2DbStrategy strategy;

    @RabbitListener(queues = CountPostLikeMqConfig.QUEUE, containerFactory = "likeUnlikeBatchListenerContainerFactory")
    public void onMessages(List<Message> messages) {
        strategy.handle(messages);
    }
}
