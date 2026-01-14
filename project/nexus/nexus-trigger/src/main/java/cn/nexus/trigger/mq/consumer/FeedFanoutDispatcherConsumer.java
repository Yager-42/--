package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.event.FeedFanoutTask;
import cn.nexus.types.event.PostPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feed fanout dispatcher：接收 PostPublishedEvent 并拆分为多个 {@link FeedFanoutTask} 切片任务。
 *
 * <p>为什么要拆？</p>
 * <ul>
 *     <li>单条发布事件可能对应超大粉丝量，直接在一次消费里跑完整 fanout 会导致耗时过长。</li>
 *     <li>切片后失败重试只重试某一片（offset+limit），避免整条 fanout 从 0 重新跑。</li>
 * </ul>
 *
 * @author codex
 * @since 2026-01-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedFanoutDispatcherConsumer {

    private final RabbitTemplate rabbitTemplate;
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IRelationRepository relationRepository;

    /**
     * fanout 切片大小，默认 200（复用现有配置键）。
     */
    @Value("${feed.fanout.batchSize:200}")
    private int batchSize;

    /**
     * 消费内容发布事件，拆分为切片任务并投递。
     *
     * @param event 发布事件
     */
    @RabbitListener(queues = FeedFanoutConfig.QUEUE)
    public void onMessage(PostPublishedEvent event) {
        try {
            dispatch(event);
        } catch (Exception e) {
            Long postId = event == null ? null : event.getPostId();
            Long authorId = event == null ? null : event.getAuthorId();
            log.error("MQ feed fanout dispatch failed, postId={}, authorId={}", postId, authorId, e);
            throw new AmqpRejectAndDontRequeueException("feed fanout dispatch failed", e);
        }
    }

    private void dispatch(PostPublishedEvent event) {
        if (event == null) {
            return;
        }
        Long postId = event.getPostId();
        Long authorId = event.getAuthorId();
        Long publishTimeMs = event.getPublishTimeMs();
        if (postId == null || authorId == null || publishTimeMs == null) {
            return;
        }

        // 1) 作者自己无条件写入 inbox：发布者体验保底（且写入天然幂等）
        feedTimelineRepository.addToInbox(authorId, postId, publishTimeMs);

        // 2) 计算切片数量并投递 fanout task（失败重试只重试某一片）
        int pageSize = Math.max(1, batchSize);
        int total = relationRepository.countFollowerIds(authorId);
        if (total <= 0) {
            return;
        }
        int slices = (total + pageSize - 1) / pageSize;
        for (int i = 0; i < slices; i++) {
            int offset = i * pageSize;
            FeedFanoutTask task = new FeedFanoutTask(postId, authorId, publishTimeMs, offset, pageSize);
            rabbitTemplate.convertAndSend(FeedFanoutConfig.EXCHANGE, FeedFanoutConfig.TASK_ROUTING_KEY, task);
        }
        log.info("feed fanout dispatched, postId={}, authorId={}, totalFollowers={}, slices={}, pageSize={}",
                postId, authorId, total, slices, pageSize);
    }
}
