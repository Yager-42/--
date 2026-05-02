package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackAMqConfig;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 推荐反馈 A 通道消费者：复用通知事件（LIKE_ADDED / COMMENT_CREATED）写入推荐系统。
 *
 * @author rr
 * @author codex
 * @since 2026-01-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRecommendFeedbackAConsumer {

    private final IRecommendationPort recommendationPort;

    /**
     * 消费 A 通道反馈事件并写入推荐系统。
     *
     * @param event 互动通知事件。 {@link InteractionNotifyEvent}
     */
    @RabbitListener(queues = FeedRecommendFeedbackAMqConfig.Q_FEED_RECOMMEND_FEEDBACK_A, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedRecommendFeedbackAConsumer", eventId = "#event.eventId", payload = "#event")
    public void onMessage(InteractionNotifyEvent event) {
        if (event == null || event.getEventType() == null) {
            return;
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("recommend feedback A eventId missing");
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

        // A 通道不是直接吃推荐反馈事件，而是复用点赞 / 评论通知事件，所以这里要先翻译成推荐端认识的反馈类型。
        Long userId = event.getFromUserId();
        Long postId = event.getPostId() == null ? event.getTargetId() : event.getPostId();
        Long tsMs = event.getTsMs();
        if (userId == null || postId == null) {
            throw new ReliableMqPermanentFailureException("recommend feedback A payload invalid");
        }
        recommendationPort.insertFeedback(userId, postId, feedbackType, tsMs);
    }
}
