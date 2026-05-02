package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.FeedRecommendItemMqConfig;
import cn.nexus.types.event.PostPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 推荐 Item 写入消费者：PostPublishedEvent -> upsertItem。
 *
 * @author rr
 * @author codex
 * @since 2026-01-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRecommendItemUpsertConsumer {

    private final IContentRepository contentRepository;
    private final IRecommendationPort recommendationPort;

    /**
     * 消费发布事件，回表读取 labels（postTypes）并写入推荐系统。
     *
     * @param event 发布事件 {@link PostPublishedEvent}
     */
    @RabbitListener(queues = FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_UPSERT, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedRecommendItemUpsertConsumer", eventId = "#event.eventId", payload = "#event")
    public void onMessage(PostPublishedEvent event) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("recommend item upsert eventId missing");
        }
        handle(event);
    }

    private void handle(PostPublishedEvent event) {
        if (event == null || event.getPostId() == null) {
            return;
        }
        Long postId = event.getPostId();
        long tsMs = event.getPublishTimeMs() == null ? System.currentTimeMillis() : event.getPublishTimeMs();

        ContentPostEntity post = contentRepository.findPost(postId);
        List<String> labels = normalizeLabels(post == null ? null : post.getPostTypes());
        recommendationPort.upsertItem(postId, labels, tsMs);
        log.info("recommend item upserted, postId={}, labelsSize={}, tsMs={}", postId, labels.size(), tsMs);
    }

    private List<String> normalizeLabels(List<String> postTypes) {
        if (postTypes == null || postTypes.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(postTypes.size());
        for (String raw : postTypes) {
            if (raw == null) {
                continue;
            }
            String label = raw.trim();
            if (label.isEmpty()) {
                continue;
            }
            if (result.contains(label)) {
                continue;
            }
            result.add(label);
        }
        return result;
    }

}
