package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 关系事件 MQ 拓扑配置：声明 follow/block 两条路由的交换机、队列和绑定关系。
 *
 * @author rr
 * @author codex
 * @since 2026-01-22
 */
@Configuration
public class RelationMqConfig {

    /**
     * EXCHANGE 字段。
     */
    public static final String EXCHANGE = "social.relation";

    /**
     * RK_FOLLOW 字段。
     */
    public static final String RK_FOLLOW = "relation.follow";
    /**
     * RK_BLOCK 字段。
     */
    public static final String RK_BLOCK = "relation.block";

    /**
     * Q_FOLLOW 字段。
     */
    public static final String Q_FOLLOW = "relation.follow.queue";
    /**
     * Q_BLOCK 字段。
     */
    public static final String Q_BLOCK = "relation.block.queue";

    /**
     * 执行 relationExchange 逻辑。
     *
     * @return 处理结果。类型：{@link DirectExchange}
     */
    @Bean
    public DirectExchange relationExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    /**
     * 执行 relationFollowQueue 逻辑。
     *
     * @return 处理结果。类型：{@link Queue}
     */
    @Bean
    public Queue relationFollowQueue() {
        return new Queue(Q_FOLLOW, true);
    }

    /**
     * 执行 relationBlockQueue 逻辑。
     *
     * @return 处理结果。类型：{@link Queue}
     */
    @Bean
    public Queue relationBlockQueue() {
        return new Queue(Q_BLOCK, true);
    }

    /**
     * 执行 relationFollowBinding 逻辑。
     *
     * @return 处理结果。类型：{@link Binding}
     */
    @Bean
    public Binding relationFollowBinding(@Qualifier("relationFollowQueue") Queue relationFollowQueue,
                                         @Qualifier("relationExchange") DirectExchange relationExchange) {
        return BindingBuilder.bind(relationFollowQueue).to(relationExchange).with(RK_FOLLOW);
    }

    /**
     * 执行 relationBlockBinding 逻辑。
     *
     * @return 处理结果。类型：{@link Binding}
     */
    @Bean
    public Binding relationBlockBinding(@Qualifier("relationBlockQueue") Queue relationBlockQueue,
                                        @Qualifier("relationExchange") DirectExchange relationExchange) {
        return BindingBuilder.bind(relationBlockQueue).to(relationExchange).with(RK_BLOCK);
    }
}
