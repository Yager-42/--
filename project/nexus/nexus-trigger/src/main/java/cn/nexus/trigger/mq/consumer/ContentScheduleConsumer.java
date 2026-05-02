package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.service.IContentService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.ContentScheduleDelayConfig;
import cn.nexus.trigger.mq.event.ContentScheduleTriggerEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 定时发布延时消息消费者。
 *
 * @author {$authorName}
 * @since 2026-01-05
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentScheduleConsumer {

    private final IContentService contentService;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    /**
     * 消费一条定时发布触发消息，并执行对应的定时任务。
     *
     * <p>关键点：</p>
     * <p>1. 幂等：同一 {@code eventId} 只允许产生一次副作用。</p>
     * <p>2. 去重：同一 {@code taskId} 用 Redis 锁收口并发，避免重复执行。</p>
     *
     * @param event 定时发布触发事件 {@link ContentScheduleTriggerEvent}
     */
    @RabbitListener(queues = ContentScheduleDelayConfig.QUEUE, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "ContentScheduleConsumer", eventId = "#event.eventId", payload = "#event")
    public void onMessage(ContentScheduleTriggerEvent event) {
        if (event == null || event.getTaskId() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("content schedule payload invalid");
        }
        Long taskId = event.getTaskId();
        // 2. 任务级去重锁：同一 taskId 同时只允许一个消费者执行（跨进程）。
        String lockKey = "content:schedule:lock:" + taskId;
        String lockVal = java.util.UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockVal, java.time.Duration.ofSeconds(60));
        if (Boolean.FALSE.equals(locked)) {
            throw new ReliableMqPermanentFailureException("content schedule lock exists");
        }
        try {
            // 3. 执行定时发布：由领域服务做状态机推进、重试计数与失败原因落库。
            OperationResultVO res = contentService.executeSchedule(taskId);
            log.info("execute schedule taskId={}, status={}, msg={}", taskId, res.getStatus(), res.getMessage());
        }
        finally {
            // 4. 释放锁：用 value 比对避免误删别人刚续上的锁。
            String val = stringRedisTemplate.opsForValue().get(lockKey);
            if (lockVal.equals(val)) {
                stringRedisTemplate.delete(lockKey);
            }
        }
    }
}
