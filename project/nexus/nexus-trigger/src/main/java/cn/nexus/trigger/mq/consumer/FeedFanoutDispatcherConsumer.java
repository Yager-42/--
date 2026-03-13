package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.adapter.repository.IFeedGlobalLatestRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.valobj.FeedAuthorCategoryEnumVO;
import cn.nexus.domain.social.service.FeedAuthorCategoryStateMachine;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.event.FeedFanoutTask;
import cn.nexus.types.event.PostPublishedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final String CONSUMER_NAME = "FeedFanoutDispatcherConsumer";

    private final RabbitTemplate rabbitTemplate;
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedOutboxRepository feedOutboxRepository;
    private final IFeedBigVPoolRepository feedBigVPoolRepository;
    private final IFeedGlobalLatestRepository feedGlobalLatestRepository;
    private final IRelationRepository relationRepository;
    private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private final FeedAuthorCategoryStateMachine feedAuthorCategoryStateMachine;
    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ObjectMapper objectMapper;

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
    public void onMessage(PostPublishedEvent event) {
        if (event == null || event.getPostId() == null || event.getAuthorId() == null
                || event.getPublishTimeMs() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("feed fanout dispatch payload invalid");
        }
        if (!consumerRecordService.start(event.getEventId(), CONSUMER_NAME, toJson(event))) {
            return;
        }
        try {
            dispatch(event);
            consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
        } catch (Exception e) {
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, e.getMessage());
            Long postId = event.getPostId();
            Long authorId = event.getAuthorId();
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
            rabbitTemplate.convertAndSend(FeedFanoutConfig.EXCHANGE, FeedFanoutConfig.TASK_ROUTING_KEY, task);
        }
        log.info("feed fanout dispatched, postId={}, authorId={}, totalFollowers={}, slices={}, pageSize={}",
                postId, authorId, followerCount, slices, pageSize);
    }

    private boolean isBigV(int followerCount) {
        if (bigvFollowerThreshold <= 0) {
            return false;
        }
        return followerCount >= bigvFollowerThreshold;
    }

    private String toJson(PostPublishedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{}";
        }
    }

}
