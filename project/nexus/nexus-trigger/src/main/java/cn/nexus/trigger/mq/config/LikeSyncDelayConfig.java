package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 点赞落库使用 RabbitMQ 延时队列（依赖 x-delayed-message 插件）。
 */
@Configuration
public class LikeSyncDelayConfig {

    public static final String EXCHANGE = "interaction.like.exchange";
    public static final String QUEUE = "interaction.like.delay.queue";
    public static final String ROUTING_KEY = "interaction.like.delay";

    public static final String DLX_EXCHANGE = "interaction.like.dlx.exchange";
    public static final String DLX_QUEUE = "interaction.like.dlx.queue";
    public static final String DLX_ROUTING_KEY = "interaction.like.dlx";

    @Bean
    public CustomExchange likeDelayExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(EXCHANGE, "x-delayed-message", true, false, args);
    }

    @Bean
    public Queue likeDelayQueue() {
        // 配置 DLX：避免消息反序列化失败等“进入不了 listener 的异常”导致消息丢失。
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY);
        return new Queue(QUEUE, true, false, false, args);
    }

    @Bean
    public Binding likeDelayBinding(Queue likeDelayQueue, CustomExchange likeDelayExchange) {
        return BindingBuilder.bind(likeDelayQueue).to(likeDelayExchange).with(ROUTING_KEY).noargs();
    }

    @Bean
    public org.springframework.amqp.core.DirectExchange likeDlxExchange() {
        return new org.springframework.amqp.core.DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue likeDlxQueue() {
        return new Queue(DLX_QUEUE, true);
    }

    @Bean
    public Binding likeDlxBinding(Queue likeDlxQueue, org.springframework.amqp.core.DirectExchange likeDlxExchange) {
        return BindingBuilder.bind(likeDlxQueue).to(likeDlxExchange).with(DLX_ROUTING_KEY);
    }
}
