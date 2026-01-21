package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通知统一事件 MQ 拓扑：interaction.notify。
 *
 * <p>复用 social.interaction Exchange（见 {@link InteractionCommentMqConfig}）。</p>
 *
 * @author codex
 * @since 2026-01-21
 */
@Configuration
public class InteractionNotifyMqConfig {

    public static final String RK_INTERACTION_NOTIFY = "interaction.notify";
    public static final String Q_INTERACTION_NOTIFY = "interaction.notify.queue";

    @Bean
    public Queue interactionNotifyQueue() {
        return new Queue(Q_INTERACTION_NOTIFY, true);
    }

    @Bean
    public Binding interactionNotifyBinding(@Qualifier("interactionNotifyQueue") Queue interactionNotifyQueue,
                                           @Qualifier("interactionExchange") DirectExchange interactionExchange) {
        return BindingBuilder.bind(interactionNotifyQueue).to(interactionExchange).with(RK_INTERACTION_NOTIFY);
    }
}

