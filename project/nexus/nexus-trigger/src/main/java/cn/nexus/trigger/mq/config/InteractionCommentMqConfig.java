package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评论相关 MQ 拓扑：创建。
 *
 * @author codex
 * @since 2026-01-14
 */
@Configuration
public class InteractionCommentMqConfig {

    public static final String EXCHANGE = "social.interaction";

    public static final String RK_COMMENT_CREATED = "comment.created";
    public static final String Q_COMMENT_CREATED = "interaction.comment.created.queue";

    @Bean
    public DirectExchange interactionExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue commentCreatedQueue() {
        return new Queue(Q_COMMENT_CREATED, true);
    }

    @Bean
    public Binding commentCreatedBinding(@Qualifier("commentCreatedQueue") Queue commentCreatedQueue,
                                         @Qualifier("interactionExchange") DirectExchange interactionExchange) {
        return BindingBuilder.bind(commentCreatedQueue).to(interactionExchange).with(RK_COMMENT_CREATED);
    }

}
