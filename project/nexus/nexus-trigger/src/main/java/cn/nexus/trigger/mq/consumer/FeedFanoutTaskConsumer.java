package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.service.IFeedDistributionService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.event.FeedFanoutTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final IFeedDistributionService feedDistributionService;

    /**
     * 消费 fanout 切片任务并执行。
     *
     * @param task 切片任务
     */
    @RabbitListener(queues = FeedFanoutConfig.TASK_QUEUE, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedFanoutTaskConsumer", eventId = "#task.eventId", payload = "#task")
    public void onMessage(FeedFanoutTask task) {
        if (task == null || task.eventId() == null || task.eventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("feed fanout task eventId missing");
        }
        feedDistributionService.fanoutSlice(
                task.postId(),
                task.authorId(),
                task.publishTimeMs(),
                task.offset(),
                task.limit()
        );
    }
}
