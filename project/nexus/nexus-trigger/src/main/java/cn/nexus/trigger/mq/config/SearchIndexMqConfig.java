package cn.nexus.trigger.mq.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

@Configuration
public class SearchIndexMqConfig {

    public static final String Q_USER_NICKNAME_CHANGED = "search.user.nickname_changed.queue";
    public static final String DLQ_USER_NICKNAME_CHANGED = "search.user.nickname_changed.dlx.queue";
    public static final String RK_USER_NICKNAME_CHANGED = "user.nickname_changed";
    public static final String RK_DLX_USER_NICKNAME_CHANGED = "search.user.nickname_changed.dlx";

    @Value("${search.mq.concurrency:2}")
    private int concurrency;

    @Value("${search.mq.prefetch:20}")
    private int prefetch;

    @Value("${search.mq.retry.maxAttempts:5}")
    private int maxAttempts;

    @Value("${search.mq.retry.initialIntervalMs:1000}")
    private long initialIntervalMs;

    @Value("${search.mq.retry.multiplier:3.0}")
    private double multiplier;

    @Value("${search.mq.retry.maxIntervalMs:60000}")
    private long maxIntervalMs;

    @Bean
    public Queue searchUserNicknameChangedQueue() {
        return queueWithDlx(Q_USER_NICKNAME_CHANGED, RK_DLX_USER_NICKNAME_CHANGED);
    }

    @Bean
    public Queue searchUserNicknameChangedDlqQueue() {
        return new Queue(DLQ_USER_NICKNAME_CHANGED, true);
    }

    @Bean
    public Binding searchUserNicknameChangedBinding(@Qualifier("searchUserNicknameChangedQueue") Queue queue,
                                                    @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(queue).to(feedExchange).with(RK_USER_NICKNAME_CHANGED);
    }

    @Bean
    public Binding searchUserNicknameChangedDlqBinding(@Qualifier("searchUserNicknameChangedDlqQueue") Queue dlq,
                                                       @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(dlq).to(feedDlxExchange).with(RK_DLX_USER_NICKNAME_CHANGED);
    }

    @Bean(name = "searchIndexListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory searchIndexListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                                    MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);

        int consumers = Math.max(1, concurrency);
        factory.setConcurrentConsumers(consumers);
        factory.setMaxConcurrentConsumers(consumers);
        factory.setPrefetchCount(Math.max(1, prefetch));
        factory.setDefaultRequeueRejected(false);

        RetryOperationsInterceptor interceptor = RetryInterceptorBuilder.stateless()
                .maxAttempts(Math.max(1, maxAttempts))
                .backOffOptions(
                        Math.max(1L, initialIntervalMs),
                        Math.max(1.0d, multiplier),
                        Math.max(1L, maxIntervalMs))
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
        factory.setAdviceChain(interceptor);
        return factory;
    }

    private Queue queueWithDlx(String name, String dlxRoutingKey) {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", FeedFanoutConfig.DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", dlxRoutingKey);
        return new Queue(name, true, false, false, args);
    }
}
