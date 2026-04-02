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
public class SearchIndexCdcMqConfig {

    public static final String EXCHANGE = "search.cdc.exchange";
    public static final String ROUTING_KEY = "post.changed";
    public static final String QUEUE = "search.post.cdc.queue";

    public static final String DLX_EXCHANGE = EXCHANGE + ".dlx";
    public static final String DLX_ROUTING_KEY = "search.post.cdc.dlx";
    public static final String DLQ = "search.post.cdc.dlq";

    @Bean
    public DirectExchange searchCdcExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange searchCdcDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue searchPostCdcQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY);
        return new Queue(QUEUE, true, false, false, args);
    }

    @Bean
    public Queue searchPostCdcDlq() {
        return new Queue(DLQ, true);
    }

    @Bean
    public Binding searchPostCdcBinding(@Qualifier("searchPostCdcQueue") Queue queue,
                                       @Qualifier("searchCdcExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY);
    }

    @Bean
    public Binding searchPostCdcDlqBinding(@Qualifier("searchPostCdcDlq") Queue dlq,
                                          @Qualifier("searchCdcDlxExchange") DirectExchange dlxExchange) {
        return BindingBuilder.bind(dlq).to(dlxExchange).with(DLX_ROUTING_KEY);
    }
}

