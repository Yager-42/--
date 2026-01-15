package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.service.ILikeSyncService;
import cn.nexus.trigger.mq.config.LikeSyncDelayConfig;
import cn.nexus.trigger.mq.producer.LikeSyncProducer;
import cn.nexus.types.event.interaction.LikeFlushTaskEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 点赞延迟 flush 消费者：窗口到期后同步 Redis -> MySQL，并按 win 状态机决定是否重排队。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncConsumer {

    private final ILikeSyncService likeSyncService;
    private final LikeSyncProducer likeSyncProducer;

    /**
     * 窗口长度（秒），默认 60s。
     */
    @Value("${interaction.like.windowSeconds:60}")
    private long windowSeconds;

    /**
     * 延迟缓冲（秒），用于吸收边界抖动，默认 10s。
     */
    @Value("${interaction.like.delayBufferSeconds:10}")
    private long delayBufferSeconds;

    @RabbitListener(queues = LikeSyncDelayConfig.QUEUE)
    public void onMessage(LikeFlushTaskEvent event) {
        if (event == null || event.getTargetId() == null || event.getTargetType() == null) {
            return;
        }

        try {
            boolean reschedule = likeSyncService.flush(event.getTargetType(), event.getTargetId());
            if (reschedule) {
                LikeFlushTaskEvent next = new LikeFlushTaskEvent();
                next.setTargetType(event.getTargetType());
                next.setTargetId(event.getTargetId());
                likeSyncProducer.sendDelay(next, delayMs());
            }
        } catch (Exception e) {
            log.error("MQ like flush failed, targetType={}, targetId={}", event.getTargetType(), event.getTargetId(), e);
            // 让 MQ 进入 DLQ：依赖 LikeSyncDelayConfig 对 delay queue 配置 DLX/DLQ。
            throw new AmqpRejectAndDontRequeueException("like flush failed", e);
        }
    }

    private long delayMs() {
        long window = Math.max(0L, windowSeconds);
        long buffer = Math.max(0L, delayBufferSeconds);
        return (window + buffer) * 1000L;
    }
}
