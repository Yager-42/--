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

@Configuration
public class ReactionEventLogMqConfig {

    public static final String ROUTING_KEY = "reaction.event.log";
    public static final String QUEUE = "reaction.event.log.queue";

    public static final String DLX_EXCHANGE = "social.interaction.reaction.event.log.dlx";
    public static final String DLQ = "reaction.event.log.dlq.queue";
    public static final String DLX_ROUTING_KEY = "reaction.event.log.dlx";

    @Bean
    public DirectExchange reactionEventLogDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue reactionEventLogQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY);
        return new Queue(QUEUE, true, false, false, args);
    }

    @Bean
    public Queue reactionEventLogDlqQueue() {
        return new Queue(DLQ, true);
    }

    @Bean
    public Binding reactionEventLogBinding(@Qualifier("reactionEventLogQueue") Queue reactionEventLogQueue,
                                           @Qualifier("interactionExchange") DirectExchange interactionExchange) {
        return BindingBuilder.bind(reactionEventLogQueue).to(interactionExchange).with(ROUTING_KEY);
    }

    @Bean
    public Binding reactionEventLogDlqBinding(@Qualifier("reactionEventLogDlqQueue") Queue reactionEventLogDlqQueue,
                                              @Qualifier("reactionEventLogDlxExchange") DirectExchange reactionEventLogDlxExchange) {
        return BindingBuilder.bind(reactionEventLogDlqQueue).to(reactionEventLogDlxExchange).with(DLX_ROUTING_KEY);
    }
}
