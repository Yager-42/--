package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.service.IFeedDistributionService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.event.FeedFanoutTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Feed fanout worker：消费 {@link FeedFanoutTask} 并执行某一片 fanout。
 *
 * @author rr
 * @author codex
 * @since 2026-01-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedFanoutTaskConsumer {

    private static final String CONSUMER_NAME = "FeedFanoutTaskConsumer";

    private final IFeedDistributionService feedDistributionService;
    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ObjectMapper objectMapper;

    /**
     * 消费 fanout 切片任务并执行。
     *
     * @param task 切片任务
     */
    @RabbitListener(queues = FeedFanoutConfig.TASK_QUEUE, containerFactory = "reliableMqListenerContainerFactory")
    public void onMessage(FeedFanoutTask task) {
        if (task == null || task.eventId() == null || task.eventId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("feed fanout task eventId missing");
        }
        if (!consumerRecordService.start(task.eventId(), CONSUMER_NAME, toJson(task))) {
            return;
        }
        try {
            feedDistributionService.fanoutSlice(
                    task.postId(),
                    task.authorId(),
                    task.publishTimeMs(),
                    task.offset(),
                    task.limit()
            );
            consumerRecordService.markDone(task.eventId(), CONSUMER_NAME);
        } catch (Exception e) {
            consumerRecordService.markFail(task.eventId(), CONSUMER_NAME, e.getMessage());
            Long postId = task.postId();
            Long authorId = task.authorId();
            log.error("MQ feed fanout task failed, postId={}, authorId={}, offset={}, limit={}",
                    postId, authorId, task.offset(), task.limit(), e);
            throw new AmqpRejectAndDontRequeueException("feed fanout task failed", e);
        }
    }

    private String toJson(FeedFanoutTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            return "{}";
        }
    }
}
