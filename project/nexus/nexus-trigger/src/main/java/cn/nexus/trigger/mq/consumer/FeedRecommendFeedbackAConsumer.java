package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackAMqConfig;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 推荐反馈 A 通道消费者：复用通知事件（LIKE_ADDED / COMMENT_CREATED）写入推荐系统。
 *
 * <p>best-effort：失败只打日志，不阻断主链路。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRecommendFeedbackAConsumer {

    private static final String CONSUMER_NAME = "FeedRecommendFeedbackAConsumer";

    private final IRecommendationPort recommendationPort;
    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = FeedRecommendFeedbackAMqConfig.Q_FEED_RECOMMEND_FEEDBACK_A, containerFactory = "reliableMqListenerContainerFactory")
    public void onMessage(InteractionNotifyEvent event) {
        if (event == null || event.getEventType() == null) {
            return;
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("recommend feedback A eventId missing");
        }
        EventType type = event.getEventType();
        String feedbackType;
        if (type == EventType.LIKE_ADDED) {
            feedbackType = "like";
        } else if (type == EventType.COMMENT_CREATED) {
            feedbackType = "comment";
        } else {
            return;
        }

        Long userId = event.getFromUserId();
        Long postId = event.getPostId() == null ? event.getTargetId() : event.getPostId();
        Long tsMs = event.getTsMs();
        if (userId == null || postId == null) {
            throw new AmqpRejectAndDontRequeueException("recommend feedback A payload invalid");
        }
        if (!consumerRecordService.start(event.getEventId(), CONSUMER_NAME, toJson(event))) {
            return;
        }
        try {
            recommendationPort.insertFeedback(userId, postId, feedbackType, tsMs);
            consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
        } catch (Exception e) {
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, e.getMessage());
            log.error("recommend feedback A failed, eventType={}, userId={}, postId={}", type.name(), userId, postId, e);
            throw new AmqpRejectAndDontRequeueException("recommend feedback A failed", e);
        }
    }

    private String toJson(InteractionNotifyEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{}";
        }
    }
}
