package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackMqConfig;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final IRecommendationPort recommendationPort;

    /**
     * 消费推荐反馈事件并写入推荐系统。
     *
     * @param event 推荐反馈事件。 {@link RecommendFeedbackEvent}
     */
    @RabbitListener(queues = FeedRecommendFeedbackMqConfig.QUEUE, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedRecommendFeedbackConsumer", eventId = "#event.eventId", payload = "#event")
    public void onMessage(RecommendFeedbackEvent event) {
        if (event == null) {
            return;
        }
        Long userId = event.getFromUserId();
        Long postId = event.getPostId();
        String feedbackType = event.getFeedbackType();
        Long tsMs = event.getTsMs();
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("recommend feedback eventId missing");
        }
        if (userId == null || postId == null || feedbackType == null || feedbackType.isBlank()) {
            throw new ReliableMqPermanentFailureException("recommend feedback payload invalid");
        }
        recommendationPort.insertFeedback(userId, postId, feedbackType.trim(), tsMs);
    }
}
