package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackMqConfig;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 推荐反馈消费者（C 通道）：RecommendFeedbackEvent -> insertFeedback。
 *
 * @author rr
 * @author codex
 * @since 2026-01-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRecommendFeedbackConsumer {

    private static final String CONSUMER_NAME = "FeedRecommendFeedbackConsumer";

    private final IRecommendationPort recommendationPort;
    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ObjectMapper objectMapper;

    /**
     * 消费推荐反馈事件并写入推荐系统。
     *
     * @param event 推荐反馈事件。 {@link RecommendFeedbackEvent}
     */
    @RabbitListener(queues = FeedRecommendFeedbackMqConfig.QUEUE, containerFactory = "reliableMqListenerContainerFactory")
    public void onMessage(RecommendFeedbackEvent event) {
        if (event == null) {
            return;
        }
        Long userId = event.getFromUserId();
        Long postId = event.getPostId();
        String feedbackType = event.getFeedbackType();
        Long tsMs = event.getTsMs();
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("recommend feedback eventId missing");
        }
        if (userId == null || postId == null || feedbackType == null || feedbackType.isBlank()) {
            throw new AmqpRejectAndDontRequeueException("recommend feedback payload invalid");
        }
        if (!consumerRecordService.start(event.getEventId(), CONSUMER_NAME, toJson(event))) {
            return;
        }
        try {
            recommendationPort.insertFeedback(userId, postId, feedbackType.trim(), tsMs);
            consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
        } catch (Exception e) {
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, e.getMessage());
            log.error("recommend feedback C failed, eventId={}, userId={}, postId={}, feedbackType={}",
                    event.getEventId(), userId, postId, feedbackType, e);
            throw new AmqpRejectAndDontRequeueException("recommend feedback failed", e);
        }
    }

    private String toJson(RecommendFeedbackEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{}";
        }
    }
}
