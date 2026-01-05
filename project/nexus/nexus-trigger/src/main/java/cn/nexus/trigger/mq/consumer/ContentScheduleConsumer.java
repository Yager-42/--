package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.service.IContentService;
import cn.nexus.trigger.mq.config.ContentScheduleDelayConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 定时发布延时消息消费者。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentScheduleConsumer {

    private final IContentService contentService;
    private final RabbitTemplate rabbitTemplate;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = ContentScheduleDelayConfig.QUEUE)
    public void onMessage(Long taskId) {
        String lockKey = "content:schedule:lock:" + taskId;
        String lockVal = java.util.UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockVal, java.time.Duration.ofSeconds(60));
        if (Boolean.FALSE.equals(locked)) {
            log.warn("skip schedule due to lock exists taskId={}", taskId);
            return;
        }
        try {
            OperationResultVO res = contentService.executeSchedule(taskId);
            log.info("execute schedule taskId={}, status={}, msg={}", taskId, res.getStatus(), res.getMessage());
        } catch (Exception e) {
            log.error("execute schedule failed, taskId={}", taskId, e);
            // 投递到死信队列
            rabbitTemplate.convertAndSend(ContentScheduleDelayConfig.DLX_EXCHANGE, ContentScheduleDelayConfig.DLX_ROUTING_KEY, taskId);
        }
        finally {
            String val = stringRedisTemplate.opsForValue().get(lockKey);
            if (lockVal.equals(val)) {
                stringRedisTemplate.delete(lockKey);
            }
        }
    }
}
