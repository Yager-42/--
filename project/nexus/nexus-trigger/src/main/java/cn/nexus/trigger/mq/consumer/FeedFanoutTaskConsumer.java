package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.service.IFeedDistributionService;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.event.FeedFanoutTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Feed fanout worker：消费 {@link FeedFanoutTask} 并执行某一片 fanout。
 *
 * <p>该 consumer 只负责“执行一片”，不负责拆片；拆片由 {@link FeedFanoutDispatcherConsumer} 完成。</p>
 *
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
    @RabbitListener(queues = FeedFanoutConfig.TASK_QUEUE)
    public void onMessage(FeedFanoutTask task) {
        try {
            if (task == null) {
                return;
            }
            feedDistributionService.fanoutSlice(
                    task.postId(),
                    task.authorId(),
                    task.publishTimeMs(),
                    task.offset(),
                    task.limit()
            );
        } catch (Exception e) {
            Long postId = task == null ? null : task.postId();
            Long authorId = task == null ? null : task.authorId();
            log.error("MQ feed fanout task failed, postId={}, authorId={}, offset={}, limit={}",
                    postId, authorId, task == null ? null : task.offset(), task == null ? null : task.limit(), e);
            throw new AmqpRejectAndDontRequeueException("feed fanout task failed", e);
        }
    }
}
