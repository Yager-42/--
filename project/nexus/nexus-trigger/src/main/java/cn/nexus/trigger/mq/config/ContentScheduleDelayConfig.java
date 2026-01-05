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
 * 定时发布使用 RabbitMQ 延时队列（依赖 x-delayed-message 插件）。
 */
@Configuration
public class ContentScheduleDelayConfig {

    public static final String EXCHANGE = "content.schedule.exchange";
    public static final String QUEUE = "content.schedule.delay.queue";
    public static final String ROUTING_KEY = "content.schedule.delay";
    public static final String DLX_EXCHANGE = "content.schedule.dlx.exchange";
    public static final String DLX_QUEUE = "content.schedule.dlx.queue";
    public static final String DLX_ROUTING_KEY = "content.schedule.dlx";

    @Bean
    public CustomExchange delayExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(EXCHANGE, "x-delayed-message", true, false, args);
    }

    @Bean
    public Queue delayQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    public Binding delayBinding(Queue delayQueue, CustomExchange delayExchange) {
        return BindingBuilder.bind(delayQueue).to(delayExchange).with(ROUTING_KEY).noargs();
    }

    @Bean
    public org.springframework.amqp.core.DirectExchange dlxExchange() {
        return new org.springframework.amqp.core.DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue dlxQueue() {
        return new Queue(DLX_QUEUE, true);
    }

    @Bean
    public Binding dlxBinding(Queue dlxQueue, org.springframework.amqp.core.DirectExchange dlxExchange) {
        return BindingBuilder.bind(dlxQueue).to(dlxExchange).with(DLX_ROUTING_KEY);
    }
}
