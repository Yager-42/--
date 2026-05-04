package cn.nexus.trigger.mq.config;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Feed follow compensation queue topology backed by relation counter follow events.
 *
 * @author codex
 * @since 2026-05-04
 */
@Configuration
public class FeedFollowCompensationMqConfig {

    @Bean
    public Queue followFeedCompensationQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", RelationCounterRouting.DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", RelationCounterRouting.RK_FOLLOW_FEED_COMPENSATE_DLX);
        return new Queue(RelationCounterRouting.Q_FOLLOW_FEED_COMPENSATE, true, false, false, args);
    }

    @Bean
    public Queue followFeedCompensationDlqQueue() {
        return new Queue(RelationCounterRouting.DLQ_FOLLOW_FEED_COMPENSATE, true);
    }

    @Bean
    public Binding followFeedCompensationBinding(
            @Qualifier("followFeedCompensationQueue") Queue followFeedCompensationQueue,
            @Qualifier("relationExchange") DirectExchange relationExchange) {
        return BindingBuilder.bind(followFeedCompensationQueue)
                .to(relationExchange)
                .with(RelationCounterRouting.RK_FOLLOW);
    }

    @Bean
    public Binding followFeedCompensationDlqBinding(
            @Qualifier("followFeedCompensationDlqQueue") Queue followFeedCompensationDlqQueue,
            @Qualifier("relationDlxExchange") DirectExchange relationDlxExchange) {
        return BindingBuilder.bind(followFeedCompensationDlqQueue)
                .to(relationDlxExchange)
                .with(RelationCounterRouting.RK_FOLLOW_FEED_COMPENSATE_DLX);
    }
}
