package cn.nexus.trigger.mq.producer;

import cn.nexus.trigger.mq.config.LikeSyncDelayConfig;
import cn.nexus.types.event.interaction.LikeFlushTaskEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 点赞 flush 延时消息生产者。
 */
@Component
@RequiredArgsConstructor
public class LikeSyncProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendDelay(LikeFlushTaskEvent event, long delayMs) {
        if (event == null) {
            return;
        }
        if (delayMs < 0) {
            delayMs = 0;
        }
        final long finalDelay = delayMs;
        rabbitTemplate.convertAndSend(
                LikeSyncDelayConfig.EXCHANGE,
                LikeSyncDelayConfig.ROUTING_KEY,
                event,
                msg -> {
                    msg.getMessageProperties().setHeader("x-delay", finalDelay);
                    return msg;
                }
        );
    }
}

