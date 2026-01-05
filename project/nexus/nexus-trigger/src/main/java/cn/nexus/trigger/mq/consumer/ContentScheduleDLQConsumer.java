package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.service.IContentService;
import cn.nexus.trigger.mq.config.ContentScheduleDelayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 定时发布死信告警消费者（占位：当前只记录日志，可接入告警系统）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentScheduleDLQConsumer {

    private final IContentService contentService;

    @RabbitListener(queues = ContentScheduleDelayConfig.DLX_QUEUE)
    public void onDLQ(Long taskId) {
        log.error("content schedule task dead-lettered, taskId={}", taskId);
        try {
            ContentScheduleEntity task = contentService.getScheduleAudit(taskId, null);
            if (task != null) {
                log.error("alert route=oncall webhook, taskId={}, userId={}, retry={}, lastError={}, status={}",
                        taskId, task.getUserId(), task.getRetryCount(), task.getLastError(), task.getStatus());
            }
        } catch (Exception e) {
            log.error("dlq alert handling failed, taskId={}", taskId, e);
        }
    }
}
