package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.ReactionSyncDelayConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 点赞同步死信告警消费者（最小实现：只记录日志）。
 *
 * @author codex
 * @since 2026-01-20
 */
@Slf4j
@Component
public class ReactionSyncDLQConsumer {

    @RabbitListener(queues = ReactionSyncDelayConfig.DLX_QUEUE)
    public void onDLQ(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        log.error("reaction sync message dead-lettered, raw={}", rawMessage);
    }
}

