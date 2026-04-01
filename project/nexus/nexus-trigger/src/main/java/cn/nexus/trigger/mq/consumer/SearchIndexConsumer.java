package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.trigger.mq.config.SearchIndexMqConfig;
import cn.nexus.trigger.search.support.SearchIndexUpsertService;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostPublishedEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import cn.nexus.types.event.UserNicknameChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexConsumer {

    private final ISearchEnginePort searchEnginePort;
    private final SearchIndexUpsertService searchIndexUpsertService;

    @RabbitListener(queues = SearchIndexMqConfig.Q_POST_PUBLISHED, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostPublished(PostPublishedEvent event) {
        if (event == null || event.getPostId() == null || event.getAuthorId() == null || event.getPublishTimeMs() == null) {
            throw new AmqpRejectAndDontRequeueException("post.published missing required fields");
        }
        handleUpsert(event.getPostId());
    }

    @RabbitListener(queues = SearchIndexMqConfig.Q_POST_UPDATED, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostUpdated(PostUpdatedEvent event) {
        if (event == null || event.getPostId() == null || event.getOperatorId() == null || event.getTsMs() == null) {
            throw new AmqpRejectAndDontRequeueException("post.updated missing required fields");
        }
        handleUpsert(event.getPostId());
    }

    @RabbitListener(queues = SearchIndexMqConfig.Q_POST_DELETED, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostDeleted(PostDeletedEvent event) {
        if (event == null || event.getPostId() == null || event.getOperatorId() == null || event.getTsMs() == null) {
            throw new AmqpRejectAndDontRequeueException("post.deleted missing required fields");
        }
        handleSoftDelete(event.getPostId(), "EVENT_DELETED");
    }

    @RabbitListener(queues = SearchIndexMqConfig.Q_USER_NICKNAME_CHANGED, containerFactory = "searchIndexListenerContainerFactory")
    public void onUserNicknameChanged(UserNicknameChangedEvent event) {
        if (event == null || event.getUserId() == null || event.getTsMs() == null) {
            throw new AmqpRejectAndDontRequeueException("user.nickname_changed missing required fields");
        }
        long startNs = System.nanoTime();
        long affected = searchIndexUpsertService.updateAuthorNickname(event.getUserId());
        log.info("event=search.index.nickname_update userId={} affected={} costMs={}",
                event.getUserId(), affected, costMs(startNs));
    }

    private void handleUpsert(Long postId) {
        long startNs = System.nanoTime();
        SearchIndexUpsertService.SearchIndexAction action = searchIndexUpsertService.upsertPost(postId);
        if (action.softDeleted()) {
            log.info("event=search.index.soft_delete contentId={} reason={} costMs={}",
                    postId, action.reason(), costMs(startNs));
            return;
        }
        log.info("event=search.index.upsert contentId={} costMs={}", postId, costMs(startNs));
    }

    private void handleSoftDelete(Long postId, String reason) {
        long startNs = System.nanoTime();
        if (postId == null) {
            return;
        }
        searchEnginePort.softDelete(postId);
        log.info("event=search.index.soft_delete contentId={} reason={} costMs={}", postId, reason, costMs(startNs));
    }

    private long costMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }
}
