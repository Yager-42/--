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
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-03
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostLikeCount2DbConsumer {

    private final PostLikeCount2DbStrategy strategy;

    /**
     * 批量消费消息。
     *
     * @param messages 消息列表。类型：{@link List}
     */
    @RabbitListener(queues = CountPostLikeMqConfig.QUEUE, containerFactory = "likeUnlikeBatchListenerContainerFactory")
    public void onMessages(List<Message> messages) {
        strategy.handle(messages);
    }
}
