package cn.nexus.trigger.mq.consumer.strategy;

import java.util.List;
import org.springframework.amqp.core.Message;

public interface PostLikeCount2SearchIndexStrategy {
    void handle(List<Message> messages);
}
