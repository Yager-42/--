package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Feed 聚合索引清理消费者。
 *
 * @author codex
 * @since 2026-05-03
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RabbitListener(queues = FeedFanoutConfig.Q_FEED_INDEX_CLEANUP, containerFactory = "reliableMqListenerContainerFactory")
public class FeedIndexCleanupConsumer {

    private final IContentRepository contentRepository;
    private final IFeedAuthorTimelineRepository feedAuthorTimelineRepository;

    @RabbitHandler
    @ReliableMqConsume(consumerName = "FeedIndexCleanupUpdatedConsumer", eventId = "#event.eventId", payload = "#event")
    public void onUpdated(PostUpdatedEvent event) {
        validate(event == null ? null : event.getEventId(), event == null ? null : event.getPostId());
        cleanupIfInvisible(event.getPostId());
    }

    @RabbitHandler
    @ReliableMqConsume(consumerName = "FeedIndexCleanupDeletedConsumer", eventId = "#event.eventId", payload = "#event")
    public void onDeleted(PostDeletedEvent event) {
        validate(event == null ? null : event.getEventId(), event == null ? null : event.getPostId());
        cleanupIfInvisible(event.getPostId());
    }

    private void cleanupIfInvisible(Long postId) {
        ContentPostEntity post = contentRepository.findPostBypassCache(postId);
        if (post == null) {
            log.info("feed index cleanup skipped because post not found, postId={}", postId);
            return;
        }
        if (Integer.valueOf(ContentPostStatusEnumVO.PUBLISHED.getCode()).equals(post.getStatus())) {
            return;
        }
        Long authorId = post.getUserId();
        feedAuthorTimelineRepository.removeFromTimeline(authorId, postId);
    }

    private void validate(String eventId, Long postId) {
        if (eventId == null || eventId.isBlank() || postId == null) {
            throw new ReliableMqPermanentFailureException("feed index cleanup payload invalid");
        }
    }
}
