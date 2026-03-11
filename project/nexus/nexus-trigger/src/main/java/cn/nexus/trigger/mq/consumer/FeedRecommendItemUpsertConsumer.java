package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.FeedRecommendItemMqConfig;
import cn.nexus.types.event.PostPublishedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 推荐 Item 写入消费者：PostPublishedEvent -> upsertItem。
 *
 * <p>旁路链路：写入失败不影响发布成功，但消费异常会进入 DLQ 方便排障与重放。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRecommendItemUpsertConsumer {

    private static final String CONSUMER_NAME = "FeedRecommendItemUpsertConsumer";

    private final IContentRepository contentRepository;
    private final IRecommendationPort recommendationPort;
    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ObjectMapper objectMapper;

    /**
     * 消费发布事件，回表读取 labels（postTypes）并写入推荐系统。
     *
     * @param event 发布事件 {@link PostPublishedEvent}
     */
    @RabbitListener(queues = FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_UPSERT, containerFactory = "reliableMqListenerContainerFactory")
    public void onMessage(PostPublishedEvent event) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("recommend item upsert eventId missing");
        }
        if (!consumerRecordService.start(event.getEventId(), CONSUMER_NAME, toJson(event))) {
            return;
        }
        try {
            handle(event);
            consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
        } catch (Exception e) {
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, e.getMessage());
            Long postId = event.getPostId();
            Long authorId = event.getAuthorId();
            log.error("MQ recommend item upsert failed, postId={}, authorId={}", postId, authorId, e);
            throw new AmqpRejectAndDontRequeueException("recommend item upsert failed", e);
        }
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

    private String toJson(PostPublishedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{}";
        }
    }
}
