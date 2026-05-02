package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.service.IContentService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.ContentScheduleDelayConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 定时发布死信告警消费者（占位：当前只记录日志，可接入告警系统）。
 *
 * @author {$authorName}
 * @since 2026-01-05
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentScheduleDLQConsumer {

    private final IContentService contentService;
    private final ObjectMapper objectMapper;

    /**
     * 处理一条定时发布死信消息：记录 DLQ 信息，并输出辅助排查日志。
     *
     * @param message 死信消息 {@link Message}
     */
    @RabbitListener(queues = ContentScheduleDelayConfig.DLX_QUEUE)
    @ReliableMqDlq(consumerName = "ContentScheduleConsumer",
            originalQueue = ContentScheduleDelayConfig.QUEUE,
            originalExchange = ContentScheduleDelayConfig.EXCHANGE,
            originalRoutingKey = ContentScheduleDelayConfig.ROUTING_KEY,
            fallbackPayloadType = "cn.nexus.trigger.mq.event.ContentScheduleTriggerEvent",
            lastError = "'content schedule dead-lettered'")
    public void onDLQ(Message message) {
        Long taskId = null;
        try {
            JsonNode root = objectMapper.readTree(message.getBody());
            JsonNode taskIdNode = root == null ? null : root.path("taskId");
            if (taskIdNode != null && !taskIdNode.isMissingNode() && !taskIdNode.isNull()) {
                taskId = taskIdNode.asLong();
            }
        } catch (Exception ignored) {
        }
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
