package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feed 写扩散 MQ 拓扑：发布内容后触发 fanout。
 *
 * @author codex
 * @since 2026-01-12
 */
@Configuration
public class FeedFanoutConfig {

    /**
     * Exchange：Feed 分发事件交换机。
     */
    public static final String EXCHANGE = "social.feed";

    /**
     * Queue：内容发布事件队列。
     */
    public static final String QUEUE = "feed.post.published.queue";

    /**
     * RoutingKey：内容发布事件路由键。
     */
    public static final String ROUTING_KEY = "post.published";

    /**
     * 声明 Feed 分发交换机（Direct）。
     *
     * @return exchange
     */
    @Bean
    public DirectExchange feedExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    /**
     * 声明内容发布队列。
     *
     * @return queue
     */
    @Bean
    public Queue feedPostPublishedQueue() {
        return new Queue(QUEUE, true);
    }

    /**
     * 声明交换机与队列的绑定关系。
     *
     * @param feedPostPublishedQueue 队列
     * @param feedExchange           交换机
     * @return binding
     */
    @Bean
    public Binding feedPostPublishedBinding(Queue feedPostPublishedQueue, DirectExchange feedExchange) {
        return BindingBuilder.bind(feedPostPublishedQueue).to(feedExchange).with(ROUTING_KEY);
    }
}

