package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.CountPostLike2SearchIndexMqConfig;
import cn.nexus.trigger.mq.consumer.strategy.SnapshotPostLikeCount2SearchIndexStrategy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostLikeCount2SearchIndexConsumer {

    private final SnapshotPostLikeCount2SearchIndexStrategy strategy;

    @RabbitListener(queues = CountPostLike2SearchIndexMqConfig.QUEUE, containerFactory = "likeUnlikeBatchListenerContainerFactory")
    public void onMessages(List<Message> messages) {
        strategy.handle(messages);
    }
}
