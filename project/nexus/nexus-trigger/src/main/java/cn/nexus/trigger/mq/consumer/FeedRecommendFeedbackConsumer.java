package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackMqConfig;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 推荐反馈消费者（C 通道）：RecommendFeedbackEvent -> insertFeedback。
 *
 * <p>best-effort：失败只打日志，不阻断主链路。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRecommendFeedbackConsumer {

    private final IRecommendationPort recommendationPort;

    @RabbitListener(queues = FeedRecommendFeedbackMqConfig.QUEUE)
    public void onMessage(RecommendFeedbackEvent event) {
        if (event == null) {
            return;
        }
        Long userId = event.getFromUserId();
        Long postId = event.getPostId();
        String feedbackType = event.getFeedbackType();
        Long tsMs = event.getTsMs();
        if (userId == null || postId == null || feedbackType == null || feedbackType.isBlank()) {
            return;
        }
        try {
            recommendationPort.insertFeedback(userId, postId, feedbackType.trim(), tsMs);
        } catch (Exception e) {
            log.warn("recommend feedback C failed, eventId={}, userId={}, postId={}, feedbackType={}",
                    event.getEventId(), userId, postId, feedbackType, e);
        }
    }
}

