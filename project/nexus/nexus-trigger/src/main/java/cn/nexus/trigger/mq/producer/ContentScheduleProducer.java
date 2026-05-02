package cn.nexus.trigger.mq.producer;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.trigger.mq.config.ContentScheduleDelayConfig;
import cn.nexus.trigger.mq.event.ContentScheduleTriggerEvent;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 定时发布延时消息生产者。
 *
 * @author {$authorName}
 * @since 2026-01-05
 */
@Component
@RequiredArgsConstructor
public class ContentScheduleProducer {

    private final ReliableMqOutboxService reliableMqOutboxService;

    /**
     * 发送一条延时触发消息（用于到点执行定时发布）。
     *
     * @param taskId 定时任务 ID {@link Long}
     * @param delayMs 延迟毫秒数（负数会被按 0 处理） {@code long}
     */
    public void sendDelay(Long taskId, long delayMs) {
        if (taskId == null) {
            return;
        }
        long safeDelayMs = Math.max(0L, delayMs);
        ContentScheduleTriggerEvent event = new ContentScheduleTriggerEvent();
        event.setTaskId(taskId);
        // Keep explicit outbox save until @ReliableMqPublish can carry message headers.
        reliableMqOutboxService.save(
                event.getEventId(),
                ContentScheduleDelayConfig.EXCHANGE,
                ContentScheduleDelayConfig.ROUTING_KEY,
                event,
                Map.of("x-delay", safeDelayMs)
        );
    }
}
