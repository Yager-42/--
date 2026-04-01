package cn.nexus.trigger.mq.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CountPostLike2SearchIndexMqConfig {

    public static final String EXCHANGE = "CountPostLike2SearchIndexTopic";
    public static final String ROUTING_KEY = "CountPostLike2SearchIndex";
    public static final String QUEUE = "count.post.like.search-index.queue";

    public static final String DLX_EXCHANGE = EXCHANGE + ".dlx";
    public static final String DLX_ROUTING_KEY = "count.post.like.search-index.dlx";
    public static final String DLQ = "count.post.like.search-index.dlq";

    @Bean
    public DirectExchange countPostLike2SearchIndexExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange countPostLike2SearchIndexDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue countPostLike2SearchIndexQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY);
        return new Queue(QUEUE, true, false, false, args);
    }

    @Bean
    public Queue countPostLike2SearchIndexDlq() {
        return new Queue(DLQ, true);
    }

    @Bean
    public Binding countPostLike2SearchIndexBinding(Queue countPostLike2SearchIndexQueue,
                                                    DirectExchange countPostLike2SearchIndexExchange) {
        return BindingBuilder.bind(countPostLike2SearchIndexQueue)
                .to(countPostLike2SearchIndexExchange)
                .with(ROUTING_KEY);
    }

    @Bean
    public Binding countPostLike2SearchIndexDlqBinding(Queue countPostLike2SearchIndexDlq,
                                                       DirectExchange countPostLike2SearchIndexDlxExchange) {
        return BindingBuilder.bind(countPostLike2SearchIndexDlq)
                .to(countPostLike2SearchIndexDlxExchange)
                .with(DLX_ROUTING_KEY);
    }
}
