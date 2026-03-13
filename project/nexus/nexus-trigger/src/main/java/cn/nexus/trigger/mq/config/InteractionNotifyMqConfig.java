package cn.nexus.trigger.mq.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通知统一事件 MQ 拓扑：interaction.notify。
 *
 * <p>复用 social.interaction Exchange（见 {@link InteractionCommentMqConfig}）。</p>
 *
 * @author codex
 * @since 2026-01-21
 */
@Configuration
public class InteractionNotifyMqConfig {

    public static final String RK_INTERACTION_NOTIFY = "interaction.notify";
    public static final String Q_INTERACTION_NOTIFY = "interaction.notify.queue";

    /** 通知队列死信交换机：消费失败必须能落地，不允许“直接丢”。 */
    public static final String DLX_EXCHANGE = "social.interaction.notify.dlx";
    public static final String DLQ_INTERACTION_NOTIFY = "interaction.notify.dlq.queue";
    public static final String RK_INTERACTION_NOTIFY_DLX = "interaction.notify.dlx";

    @Bean
    public DirectExchange interactionNotifyDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue interactionNotifyQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", RK_INTERACTION_NOTIFY_DLX);
        return new Queue(Q_INTERACTION_NOTIFY, true, false, false, args);
    }

    @Bean
    public Queue interactionNotifyDlqQueue() {
        return new Queue(DLQ_INTERACTION_NOTIFY, true);
    }

    @Bean
    public Binding interactionNotifyDlqBinding(@Qualifier("interactionNotifyDlqQueue") Queue interactionNotifyDlqQueue,
                                              @Qualifier("interactionNotifyDlxExchange") DirectExchange interactionNotifyDlxExchange) {
        return BindingBuilder.bind(interactionNotifyDlqQueue).to(interactionNotifyDlxExchange).with(RK_INTERACTION_NOTIFY_DLX);
    }

    @Bean
    public Binding interactionNotifyBinding(@Qualifier("interactionNotifyQueue") Queue interactionNotifyQueue,
                                           @Qualifier("interactionExchange") DirectExchange interactionExchange) {
        return BindingBuilder.bind(interactionNotifyQueue).to(interactionExchange).with(RK_INTERACTION_NOTIFY);
    }
}
