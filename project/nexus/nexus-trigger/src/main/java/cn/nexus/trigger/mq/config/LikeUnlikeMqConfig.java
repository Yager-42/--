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
 * Like/Unlike MQ topology (RabbitMQ adapter for RocketMQ LikeUnlikeTopic).
 */
@Configuration
public class LikeUnlikeMqConfig {

    public static final String EXCHANGE = "LikeUnlikeTopic";

    public static final String DLX_EXCHANGE = EXCHANGE + ".dlx";

    public static final String DLX_RK_COUNT = "like.unlike.count.dlx";

    public static final String DLQ_COUNT = "like.unlike.count.dlq";

    public static final String RK_LIKE = "Like";
    public static final String RK_UNLIKE = "Unlike";

    /**
     * ConsumerGroup B: aggregate counts (BufferTrigger).
     */
    public static final String QUEUE_COUNT = "like.unlike.count.queue";

    @Bean
    public DirectExchange likeUnlikeExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange likeUnlikeDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue likeUnlikeCountQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_RK_COUNT);
        return new Queue(QUEUE_COUNT, true, false, false, args);
    }

    @Bean
    public Queue likeUnlikeCountDlq() {
        return new Queue(DLQ_COUNT, true);
    }

    @Bean
    public Binding likeUnlikeCountDlqBinding(Queue likeUnlikeCountDlq, DirectExchange likeUnlikeDlxExchange) {
        return BindingBuilder.bind(likeUnlikeCountDlq).to(likeUnlikeDlxExchange).with(DLX_RK_COUNT);
    }

    @Bean
    public Binding likeUnlikeCountLikeBinding(Queue likeUnlikeCountQueue, DirectExchange likeUnlikeExchange) {
        return BindingBuilder.bind(likeUnlikeCountQueue).to(likeUnlikeExchange).with(RK_LIKE);
    }

    @Bean
    public Binding likeUnlikeCountUnlikeBinding(Queue likeUnlikeCountQueue, DirectExchange likeUnlikeExchange) {
        return BindingBuilder.bind(likeUnlikeCountQueue).to(likeUnlikeExchange).with(RK_UNLIKE);
    }
}
