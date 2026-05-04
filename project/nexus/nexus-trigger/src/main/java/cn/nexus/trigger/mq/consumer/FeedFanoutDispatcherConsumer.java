package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.valobj.FeedAuthorCategoryEnumVO;
import cn.nexus.domain.social.service.FeedAuthorCategoryStateMachine;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.trigger.mq.producer.FeedFanoutTaskProducer;
import cn.nexus.types.event.FeedFanoutTask;
import cn.nexus.types.event.PostPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feed fanout dispatcher：接收 PostPublishedEvent 并拆分为多个 {@link FeedFanoutTask} 切片任务。
 *
 * @author rr
 * @author codex
 * @since 2026-01-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedFanoutDispatcherConsumer {

    private final FeedFanoutTaskProducer feedFanoutTaskProducer;
    private final IFeedAuthorTimelineRepository feedAuthorTimelineRepository;
    private final IRelationRepository relationRepository;
    private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private final FeedAuthorCategoryStateMachine feedAuthorCategoryStateMachine;

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
    @RabbitListener(queues = FeedFanoutConfig.QUEUE, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedFanoutDispatcherConsumer", eventId = "#event.eventId", payload = "#event")
    public void onMessage(PostPublishedEvent event) {
        if (event == null || event.getPostId() == null || event.getAuthorId() == null
                || event.getPublishTimeMs() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("feed fanout dispatch payload invalid");
        }
        dispatch(event);
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

        // 0) 永远写 AuthorTimeline：作者侧发布流索引（幂等）
        feedAuthorTimelineRepository.addToTimeline(authorId, postId, publishTimeMs);

        // 1) 大 V 默认不做“全量写扩散”，避免发布一条写入海量粉丝 inbox
        Integer category = feedAuthorCategoryRepository.getCategory(authorId);
        if (category == null) {
            feedAuthorCategoryStateMachine.onFollowerCountChanged(authorId);
            category = feedAuthorCategoryRepository.getCategory(authorId);
        }
        if (category != null && category == FeedAuthorCategoryEnumVO.BIGV.getCode()) {
            log.info("skip fanout for bigv author, postId={}, authorId={}, category={}", postId, authorId, category);
            return;
        }

        // 2) 计算切片数量并投递 fanout task（失败重试只重试某一片）
        int followerCount = relationRepository.countFollowerIds(authorId);
        int pageSize = Math.max(1, batchSize);
        if (followerCount <= 0) {
            return;
        }
        int slices = (followerCount + pageSize - 1) / pageSize;
        for (int i = 0; i < slices; i++) {
            int offset = i * pageSize;
            String taskEventId = (event.getEventId() == null ? "feed-fanout" : event.getEventId()) + ":" + offset + ":" + pageSize;
            FeedFanoutTask task = new FeedFanoutTask(taskEventId, postId, authorId, publishTimeMs, offset, pageSize);
            feedFanoutTaskProducer.publish(task);
        }
        log.info("feed fanout dispatched, postId={}, authorId={}, totalFollowers={}, slices={}, pageSize={}",
                postId, authorId, followerCount, slices, pageSize);
    }

}
