package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Count(Post Like) -> DB alignment MQ topology.
 */
@Configuration
public class CountPostLikeMqConfig {

    public static final String EXCHANGE = "CountPostLike2DBTopic";
    public static final String ROUTING_KEY = "CountPostLike2DB";
    public static final String QUEUE = "count.post.like.db.queue";

    public static final String DLX_EXCHANGE = EXCHANGE + ".dlx";
    public static final String DLX_ROUTING_KEY = "count.post.like.db.dlx";
    public static final String DLQ = "count.post.like.db.dlq";

    @Bean
    public DirectExchange countPostLikeExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange countPostLikeDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue countPostLikeQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY);
        return new Queue(QUEUE, true, false, false, args);
    }

    @Bean
    public Queue countPostLikeDlq() {
        return new Queue(DLQ, true);
    }

    @Bean
    public Binding countPostLikeBinding(Queue countPostLikeQueue, DirectExchange countPostLikeExchange) {
        return BindingBuilder.bind(countPostLikeQueue).to(countPostLikeExchange).with(ROUTING_KEY);
    }

    @Bean
    public Binding countPostLikeDlqBinding(Queue countPostLikeDlq, DirectExchange countPostLikeDlxExchange) {
        return BindingBuilder.bind(countPostLikeDlq).to(countPostLikeDlxExchange).with(DLX_ROUTING_KEY);
    }
}
