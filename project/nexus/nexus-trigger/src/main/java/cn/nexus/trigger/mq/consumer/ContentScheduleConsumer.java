package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.service.IContentService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.ContentScheduleDelayConfig;
import cn.nexus.trigger.mq.event.ContentScheduleTriggerEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 定时发布延时消息消费者。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentScheduleConsumer {

    private static final String CONSUMER_NAME = "ContentScheduleConsumer";

    private final IContentService contentService;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = ContentScheduleDelayConfig.QUEUE, containerFactory = "reliableMqListenerContainerFactory")
    public void onMessage(ContentScheduleTriggerEvent event) {
        if (event == null || event.getTaskId() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("content schedule payload invalid");
        }
        if (!consumerRecordService.start(event.getEventId(), CONSUMER_NAME, toJson(event))) {
            return;
        }
        Long taskId = event.getTaskId();
        String lockKey = "content:schedule:lock:" + taskId;
        String lockVal = java.util.UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockVal, java.time.Duration.ofSeconds(60));
        if (Boolean.FALSE.equals(locked)) {
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, "LOCK_EXISTS");
            throw new AmqpRejectAndDontRequeueException("content schedule lock exists");
        }
        try {
            OperationResultVO res = contentService.executeSchedule(taskId);
            consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
            log.info("execute schedule taskId={}, status={}, msg={}", taskId, res.getStatus(), res.getMessage());
        } catch (Exception e) {
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, e.getMessage());
            log.error("execute schedule failed, taskId={}", taskId, e);
            throw new AmqpRejectAndDontRequeueException("execute schedule failed", e);
        }
        finally {
            String val = stringRedisTemplate.opsForValue().get(lockKey);
            if (lockVal.equals(val)) {
                stringRedisTemplate.delete(lockKey);
            }
        }
    }

    private String toJson(ContentScheduleTriggerEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{}";
        }
    }
}
