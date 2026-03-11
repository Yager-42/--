package cn.nexus.trigger.mq.producer;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.trigger.mq.config.ContentScheduleDelayConfig;
import cn.nexus.trigger.mq.event.ContentScheduleTriggerEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 定时发布延时消息生产者。
 */
@Component
@RequiredArgsConstructor
public class ContentScheduleProducer {

    private final ReliableMqOutboxService reliableMqOutboxService;

    public void sendDelay(Long taskId, long delayMs) {
        if (taskId == null) {
            return;
        }
        long safeDelayMs = Math.max(0L, delayMs);
        ContentScheduleTriggerEvent event = new ContentScheduleTriggerEvent();
        event.setTaskId(taskId);
        reliableMqOutboxService.save(
                event.getEventId(),
                ContentScheduleDelayConfig.EXCHANGE,
                ContentScheduleDelayConfig.ROUTING_KEY,
                event,
                java.util.Map.of("x-delay", safeDelayMs)
        );
    }
}
