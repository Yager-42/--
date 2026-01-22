package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feed 铁粉集合生成 MQ 拓扑：消费 {@code interaction.notify}，生成 {@code feed:corefans:{authorId}}。
 *
 * <p>注意：必须使用“独立队列”，不能与 {@code interaction.notify.queue} 共享，否则会与通知消费者抢消息。</p>
 *
 * @author codex
 * @since 2026-01-22
 */
@Configuration
public class FeedCoreFansMqConfig {

    /**
     * Queue：Feed core fans 生成队列。
     */
    public static final String Q_FEED_CORE_FANS = "feed.corefans.queue";

    @Bean
    public Queue feedCoreFansQueue() {
        return new Queue(Q_FEED_CORE_FANS, true);
    }

    @Bean
    public Binding feedCoreFansBinding(@Qualifier("feedCoreFansQueue") Queue feedCoreFansQueue,
                                       @Qualifier("interactionExchange") DirectExchange interactionExchange) {
        return BindingBuilder.bind(feedCoreFansQueue)
                .to(interactionExchange)
                .with(InteractionNotifyMqConfig.RK_INTERACTION_NOTIFY);
    }
}

