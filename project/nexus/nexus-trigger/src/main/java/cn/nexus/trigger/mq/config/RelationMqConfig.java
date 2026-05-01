package cn.nexus.trigger.mq.config;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

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
    public static final String EXCHANGE = RelationCounterRouting.EXCHANGE;

    /**
     * RK_FOLLOW 字段。
     */
    public static final String RK_FOLLOW = RelationCounterRouting.RK_FOLLOW;
    /**
     * RK_BLOCK 字段。
     */
    public static final String RK_BLOCK = RelationCounterRouting.RK_BLOCK;

    /**
     * Q_FOLLOW 字段。
     */
    public static final String Q_FOLLOW = RelationCounterRouting.Q_FOLLOW;
    /**
     * Q_BLOCK 字段。
     */
    public static final String Q_BLOCK = RelationCounterRouting.Q_BLOCK;

    public static final String DLX_EXCHANGE = RelationCounterRouting.DLX_EXCHANGE;
    public static final String DLQ_FOLLOW = RelationCounterRouting.DLQ_FOLLOW;
    public static final String DLQ_BLOCK = RelationCounterRouting.DLQ_BLOCK;
    public static final String RK_FOLLOW_DLX = RelationCounterRouting.RK_FOLLOW_DLX;
    public static final String RK_BLOCK_DLX = RelationCounterRouting.RK_BLOCK_DLX;

    /**
     * 执行 relationExchange 逻辑。
     *
     * @return 处理结果。类型：{@link DirectExchange}
     */
    @Bean
    public DirectExchange relationExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange relationDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    /**
     * 执行 relationFollowQueue 逻辑。
     *
     * @return 处理结果。类型：{@link Queue}
     */
    @Bean
    public Queue relationFollowQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", RK_FOLLOW_DLX);
        return new Queue(Q_FOLLOW, true, false, false, args);
    }

    /**
     * 执行 relationBlockQueue 逻辑。
     *
     * @return 处理结果。类型：{@link Queue}
     */
    @Bean
    public Queue relationBlockQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", RK_BLOCK_DLX);
        return new Queue(Q_BLOCK, true, false, false, args);
    }

    @Bean
    public Queue relationFollowDlqQueue() {
        return new Queue(DLQ_FOLLOW, true);
    }

    @Bean
    public Queue relationBlockDlqQueue() {
        return new Queue(DLQ_BLOCK, true);
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

    @Bean
    public Binding relationFollowDlqBinding(@Qualifier("relationFollowDlqQueue") Queue relationFollowDlqQueue,
                                            @Qualifier("relationDlxExchange") DirectExchange relationDlxExchange) {
        return BindingBuilder.bind(relationFollowDlqQueue).to(relationDlxExchange).with(RK_FOLLOW_DLX);
    }

    @Bean
    public Binding relationBlockDlqBinding(@Qualifier("relationBlockDlqQueue") Queue relationBlockDlqQueue,
                                           @Qualifier("relationDlxExchange") DirectExchange relationDlxExchange) {
        return BindingBuilder.bind(relationBlockDlqQueue).to(relationDlxExchange).with(RK_BLOCK_DLX);
    }

}
