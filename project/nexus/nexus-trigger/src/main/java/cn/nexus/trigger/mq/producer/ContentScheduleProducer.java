package cn.nexus.trigger.mq.producer;

import cn.nexus.trigger.mq.config.ContentScheduleDelayConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 定时发布延时消息生产者。
 */
@Component
@RequiredArgsConstructor
public class ContentScheduleProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendDelay(Long taskId, long delayMs) {
        if (delayMs < 0) {
            delayMs = 0;
        }
        final long finalDelay = delayMs;
        rabbitTemplate.convertAndSend(
                ContentScheduleDelayConfig.EXCHANGE,
                ContentScheduleDelayConfig.ROUTING_KEY,
                taskId,
                msg -> {
                    msg.getMessageProperties().setHeader("x-delay", finalDelay);
                    return msg;
                });
    }
}
