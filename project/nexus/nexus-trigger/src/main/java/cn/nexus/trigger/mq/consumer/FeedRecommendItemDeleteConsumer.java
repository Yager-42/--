package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.FeedRecommendItemMqConfig;
import cn.nexus.types.event.PostDeletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 推荐 Item 删除消费者：PostDeletedEvent -> deleteItem。
 *
 * @author rr
 * @author codex
 * @since 2026-01-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRecommendItemDeleteConsumer {

    private static final String CONSUMER_NAME = "FeedRecommendItemDeleteConsumer";

    private final IRecommendationPort recommendationPort;
    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ObjectMapper objectMapper;

    /**
     * 消费删帖事件并同步删除推荐系统里的 Item。
     *
     * @param event 删帖事件。 {@link PostDeletedEvent}
     */
    @RabbitListener(queues = FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_DELETE, containerFactory = "reliableMqListenerContainerFactory")
    public void onMessage(PostDeletedEvent event) {
        if (event == null || event.getPostId() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("recommend item delete payload invalid");
        }
        if (!consumerRecordService.start(event.getEventId(), CONSUMER_NAME, toJson(event))) {
            return;
        }
        try {
            recommendationPort.deleteItem(event.getPostId());
            consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
        } catch (Exception e) {
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, e.getMessage());
            log.error("recommend item delete failed, eventId={}, postId={}, operatorId={}",
                    event.getEventId(), event.getPostId(), event.getOperatorId(), e);
            throw new AmqpRejectAndDontRequeueException("recommend item delete failed", e);
        }
    }

    private String toJson(PostDeletedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{}";
        }
    }
}
