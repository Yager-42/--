package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRecommendFeedbackEventPort;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 推荐反馈事件发布端口实现：使用 RabbitMQ 直接投递。
 *
 * @author codex
 * @since 2026-01-26
 */
@Component
@RequiredArgsConstructor
public class RecommendFeedbackEventPort implements IRecommendFeedbackEventPort {

    private static final String EXCHANGE = "social.recommend";
    private static final String RK_RECOMMEND_FEEDBACK = "recommend.feedback";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(RecommendFeedbackEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, RK_RECOMMEND_FEEDBACK, event);
    }
}

