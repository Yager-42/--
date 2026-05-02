package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.adapter.repository.IFeedGlobalLatestRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
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
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedOutboxRepository feedOutboxRepository;
    private final IFeedBigVPoolRepository feedBigVPoolRepository;
    private final IFeedGlobalLatestRepository feedGlobalLatestRepository;
    private final IRelationRepository relationRepository;
    private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private final FeedAuthorCategoryStateMachine feedAuthorCategoryStateMachine;

    /**
     * fanout 切片大小，默认 200（复用现有配置键）。
     */
    @Value("${feed.fanout.batchSize:200}")
    private int batchSize;

    /**
     * 大 V 判定阈值：粉丝数 >= 阈值则默认不做“全量写扩散”，改为读侧拉 Outbox（默认 500000）。
     *
     * <p>阈值 <= 0 表示禁用大 V 逻辑（始终走普通 fanout）。</p>
     */
    @Value("${feed.bigv.followerThreshold:500000}")
    private int bigvFollowerThreshold;

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

        // 0) 永远写 Outbox：后续大 V 拉模式与聚合池都依赖它（幂等）
        feedOutboxRepository.addToOutbox(authorId, postId, publishTimeMs);

        // 1) 作者自己无条件写入 inbox：发布者体验保底（且写入天然幂等）
        feedTimelineRepository.addToInbox(authorId, postId, publishTimeMs);

        // 1.5) 写入全站 latest：推荐系统不可用时的兜底候选源（旁路，不影响 fanout 语义）
        feedGlobalLatestRepository.addToLatest(postId, publishTimeMs);

        // 2) 大 V 默认不做“全量写扩散”，避免发布一条写入海量粉丝 inbox
        Integer category = feedAuthorCategoryRepository.getCategory(authorId);
        if (category == null) {
            feedAuthorCategoryStateMachine.onFollowerCountChanged(authorId);
            category = feedAuthorCategoryRepository.getCategory(authorId);
        }
        if (category != null && category == FeedAuthorCategoryEnumVO.BIGV.getCode()) {
            feedBigVPoolRepository.addToPool(authorId, postId, publishTimeMs);
            log.info("skip fanout for bigv author, postId={}, authorId={}, category={}", postId, authorId, category);
            return;
        }

        // 3) 计算切片数量并投递 fanout task（失败重试只重试某一片）
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
