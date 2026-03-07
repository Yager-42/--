package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 关系事件 MQ 拓扑：follow/block。
 */
@Configuration
public class RelationMqConfig {

    public static final String EXCHANGE = "social.relation";

    public static final String RK_FOLLOW = "relation.follow";
    public static final String RK_BLOCK = "relation.block";

    public static final String Q_FOLLOW = "relation.follow.queue";
    public static final String Q_BLOCK = "relation.block.queue";

    @Bean
    public DirectExchange relationExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue relationFollowQueue() {
        return new Queue(Q_FOLLOW, true);
    }

    @Bean
    public Queue relationBlockQueue() {
        return new Queue(Q_BLOCK, true);
    }

    @Bean
    public Binding relationFollowBinding(@Qualifier("relationFollowQueue") Queue relationFollowQueue,
                                         @Qualifier("relationExchange") DirectExchange relationExchange) {
        return BindingBuilder.bind(relationFollowQueue).to(relationExchange).with(RK_FOLLOW);
    }

    @Bean
    public Binding relationBlockBinding(@Qualifier("relationBlockQueue") Queue relationBlockQueue,
                                        @Qualifier("relationExchange") DirectExchange relationExchange) {
        return BindingBuilder.bind(relationBlockQueue).to(relationExchange).with(RK_BLOCK);
    }
}
