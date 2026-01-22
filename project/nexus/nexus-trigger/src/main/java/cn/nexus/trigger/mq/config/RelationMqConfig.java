package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 关系事件 MQ 拓扑：follow/friend/block。
 *
 * <p>目的：补齐 {@code social.relation} 交换机与 {@code relation.*.queue} 的绑定关系，
 * 确保 domain 通过 {@code RelationEventPort} 投递的事件能被 trigger 层 listener 消费。</p>
 *
 * @author codex
 * @since 2026-01-22
 */
@Configuration
public class RelationMqConfig {

    /**
     * Exchange：关系事件交换机（Direct）。
     */
    public static final String EXCHANGE = "social.relation";

    public static final String RK_FOLLOW = "relation.follow";
    public static final String RK_FRIEND = "relation.friend";
    public static final String RK_BLOCK = "relation.block";

    public static final String Q_FOLLOW = "relation.follow.queue";
    public static final String Q_FRIEND = "relation.friend.queue";
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
    public Queue relationFriendQueue() {
        return new Queue(Q_FRIEND, true);
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
    public Binding relationFriendBinding(@Qualifier("relationFriendQueue") Queue relationFriendQueue,
                                        @Qualifier("relationExchange") DirectExchange relationExchange) {
        return BindingBuilder.bind(relationFriendQueue).to(relationExchange).with(RK_FRIEND);
    }

    @Bean
    public Binding relationBlockBinding(@Qualifier("relationBlockQueue") Queue relationBlockQueue,
                                       @Qualifier("relationExchange") DirectExchange relationExchange) {
        return BindingBuilder.bind(relationBlockQueue).to(relationExchange).with(RK_BLOCK);
    }
}

