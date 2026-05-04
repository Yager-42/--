package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.adapter.repository.IFeedGlobalLatestRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class FeedIndexCleanupConsumer {

    private final IContentRepository contentRepository;
    private final IFeedOutboxRepository feedOutboxRepository;
    private final IFeedBigVPoolRepository feedBigVPoolRepository;
    private final IFeedGlobalLatestRepository feedGlobalLatestRepository;

    @RabbitListener(queues = FeedFanoutConfig.Q_FEED_INDEX_CLEANUP, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedIndexCleanupUpdatedConsumer", eventId = "#event.eventId", payload = "#event")
    public void onUpdated(PostUpdatedEvent event) {
        validate(event == null ? null : event.getEventId(), event == null ? null : event.getPostId(),
                "feed index cleanup updated payload invalid");
        cleanupIfInvisible(event.getPostId());
    }

    @RabbitListener(queues = FeedFanoutConfig.Q_FEED_INDEX_CLEANUP, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedIndexCleanupDeletedConsumer", eventId = "#event.eventId", payload = "#event")
    public void onDeleted(PostDeletedEvent event) {
        validate(event == null ? null : event.getEventId(), event == null ? null : event.getPostId(),
                "feed index cleanup deleted payload invalid");
        cleanupIfInvisible(event.getPostId());
    }

    private void cleanupIfInvisible(Long postId) {
        ContentPostEntity post = contentRepository.findPostBypassCache(postId);
        if (post == null) {
            removeLatest(postId);
            return;
        }
        if (Integer.valueOf(ContentPostStatusEnumVO.PUBLISHED.getCode()).equals(post.getStatus())) {
            return;
        }
        Long authorId = post.getUserId();
        removeOutbox(authorId, postId);
        removePool(authorId, postId);
        removeLatest(postId);
    }

    private void validate(String eventId, Long postId, String message) {
        if (eventId == null || eventId.isBlank() || postId == null) {
            throw new ReliableMqPermanentFailureException(message);
        }
    }

    private void removeOutbox(Long authorId, Long postId) {
        try {
            feedOutboxRepository.removeFromOutbox(authorId, postId);
        } catch (Exception e) {
            log.warn("feed index cleanup remove outbox failed, authorId={}, postId={}", authorId, postId, e);
        }
    }

    private void removePool(Long authorId, Long postId) {
        try {
            feedBigVPoolRepository.removeFromPool(authorId, postId);
        } catch (Exception e) {
            log.warn("feed index cleanup remove bigV pool failed, authorId={}, postId={}", authorId, postId, e);
        }
    }

    private void removeLatest(Long postId) {
        try {
            feedGlobalLatestRepository.removeFromLatest(postId);
        } catch (Exception e) {
            log.warn("feed index cleanup remove global latest failed, postId={}", postId, e);
        }
    }
}
