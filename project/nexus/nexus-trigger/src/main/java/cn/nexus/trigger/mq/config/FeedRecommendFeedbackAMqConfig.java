package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 推荐反馈 A 通道 MQ 拓扑：复用 {@code interaction.notify} 事件（LIKE_ADDED / COMMENT_CREATED）。
 * <p>注意：必须使用独立队列，不能与 {@code interaction.notify.queue} 共享，否则会与通知消费者抢消息。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Configuration
public class FeedRecommendFeedbackAMqConfig {

    /** Queue：推荐反馈 A 通道队列（独立）。 */
    public static final String Q_FEED_RECOMMEND_FEEDBACK_A = "feed.recommend.feedback.a.queue";

    @Bean
    public Queue feedRecommendFeedbackAQueue() {
        return new Queue(Q_FEED_RECOMMEND_FEEDBACK_A, true);
    }

    @Bean
    public Binding feedRecommendFeedbackABinding(@Qualifier("feedRecommendFeedbackAQueue") Queue feedRecommendFeedbackAQueue,
                                                 @Qualifier("interactionExchange") DirectExchange interactionExchange) {
        return BindingBuilder.bind(feedRecommendFeedbackAQueue)
                .to(interactionExchange)
                .with(InteractionNotifyMqConfig.RK_INTERACTION_NOTIFY);
    }
}
