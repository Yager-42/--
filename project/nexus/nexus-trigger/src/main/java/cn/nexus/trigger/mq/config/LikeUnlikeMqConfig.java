package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legacy Like/Unlike exchange kept only so stale outbox rows can be drained safely.
 * Object like count aggregation no longer binds RabbitMQ queues here.
 */
@Configuration
public class LikeUnlikeMqConfig {

    public static final String EXCHANGE = "LikeUnlikeTopic";

    public static final String RK_LIKE = "Like";
    public static final String RK_UNLIKE = "Unlike";

    @Bean
    public DirectExchange likeUnlikeExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }
}
