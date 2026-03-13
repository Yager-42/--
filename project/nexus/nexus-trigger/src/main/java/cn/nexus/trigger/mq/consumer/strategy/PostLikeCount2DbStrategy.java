package cn.nexus.trigger.mq.consumer.strategy;

import org.springframework.amqp.core.Message;

import java.util.List;

public interface PostLikeCount2DbStrategy {
    void handle(List<Message> messages);
}

