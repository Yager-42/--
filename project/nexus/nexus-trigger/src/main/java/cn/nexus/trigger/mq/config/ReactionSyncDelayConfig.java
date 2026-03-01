package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 点赞同步使用 RabbitMQ 延时队列（依赖 x-delayed-message 插件）。
 *
 * @author codex
 * @since 2026-01-20
 */
@Configuration
@ConditionalOnProperty(name = "reaction.sync.mode", havingValue = "rabbit")
public class ReactionSyncDelayConfig {

    public static final String EXCHANGE = "reaction.sync.exchange";
    public static final String QUEUE = "reaction.sync.delay.queue";
    public static final String ROUTING_KEY = "reaction.sync.delay";
    public static final String DLX_EXCHANGE = "reaction.sync.dlx.exchange";
    public static final String DLX_QUEUE = "reaction.sync.dlx.queue";
    public static final String DLX_ROUTING_KEY = "reaction.sync.dlx";

    @Bean
    public CustomExchange reactionSyncDelayExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(EXCHANGE, "x-delayed-message", true, false, args);
    }

    @Bean
    public Queue reactionSyncDelayQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    public Binding reactionSyncDelayBinding(Queue reactionSyncDelayQueue, CustomExchange reactionSyncDelayExchange) {
        return BindingBuilder.bind(reactionSyncDelayQueue).to(reactionSyncDelayExchange).with(ROUTING_KEY).noargs();
    }

    @Bean
    public org.springframework.amqp.core.DirectExchange reactionSyncDlxExchange() {
        return new org.springframework.amqp.core.DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue reactionSyncDlxQueue() {
        return new Queue(DLX_QUEUE, true);
    }

    @Bean
    public Binding reactionSyncDlxBinding(Queue reactionSyncDlxQueue,
                                         org.springframework.amqp.core.DirectExchange reactionSyncDlxExchange) {
        return BindingBuilder.bind(reactionSyncDlxQueue).to(reactionSyncDlxExchange).with(DLX_ROUTING_KEY);
    }
}

