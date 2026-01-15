package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.LikeSyncDelayConfig;
import cn.nexus.types.event.interaction.LikeFlushTaskEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 点赞 flush 死信告警消费者（占位：当前只记录日志，可接入告警系统）。
 */
@Slf4j
@Component
public class LikeSyncDLQConsumer {

    @RabbitListener(queues = LikeSyncDelayConfig.DLX_QUEUE)
    public void onDLQ(LikeFlushTaskEvent event) {
        if (event == null) {
            log.error("like flush dead-lettered, event=null");
            return;
        }
        log.error("like flush dead-lettered, targetType={}, targetId={}", event.getTargetType(), event.getTargetId());
    }
}

