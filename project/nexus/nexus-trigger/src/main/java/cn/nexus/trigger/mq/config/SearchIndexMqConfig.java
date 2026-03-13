package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

import java.util.HashMap;
import java.util.Map;

/**
 * Search 索引更新 MQ 拓扑：消费 social.feed 上的 post/user 事件，驱动 ES 索引更新。
 */
@Configuration
public class SearchIndexMqConfig {

    public static final String Q_POST_PUBLISHED = "search.post.published.queue";
    public static final String Q_POST_UPDATED = "search.post.updated.queue";
    public static final String Q_POST_DELETED = "search.post.deleted.queue";
    public static final String Q_USER_NICKNAME_CHANGED = "search.user.nickname_changed.queue";

    public static final String DLQ_POST_PUBLISHED = "search.post.published.dlx.queue";
    public static final String DLQ_POST_UPDATED = "search.post.updated.dlx.queue";
    public static final String DLQ_POST_DELETED = "search.post.deleted.dlx.queue";
    public static final String DLQ_USER_NICKNAME_CHANGED = "search.user.nickname_changed.dlx.queue";

    public static final String RK_POST_PUBLISHED = "post.published";
    public static final String RK_POST_UPDATED = "post.updated";
    public static final String RK_POST_DELETED = "post.deleted";
    public static final String RK_USER_NICKNAME_CHANGED = "user.nickname_changed";

    public static final String RK_DLX_POST_PUBLISHED = "search.post.published.dlx";
    public static final String RK_DLX_POST_UPDATED = "search.post.updated.dlx";
    public static final String RK_DLX_POST_DELETED = "search.post.deleted.dlx";
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
    public Queue searchPostPublishedQueue() {
        return queueWithDlx(Q_POST_PUBLISHED, RK_DLX_POST_PUBLISHED);
    }

    @Bean
    public Queue searchPostUpdatedQueue() {
        return queueWithDlx(Q_POST_UPDATED, RK_DLX_POST_UPDATED);
    }

    @Bean
    public Queue searchPostDeletedQueue() {
        return queueWithDlx(Q_POST_DELETED, RK_DLX_POST_DELETED);
    }

    @Bean
    public Queue searchUserNicknameChangedQueue() {
        return queueWithDlx(Q_USER_NICKNAME_CHANGED, RK_DLX_USER_NICKNAME_CHANGED);
    }

    @Bean
    public Queue searchPostPublishedDlqQueue() {
        return new Queue(DLQ_POST_PUBLISHED, true);
    }

    @Bean
    public Queue searchPostUpdatedDlqQueue() {
        return new Queue(DLQ_POST_UPDATED, true);
    }

    @Bean
    public Queue searchPostDeletedDlqQueue() {
        return new Queue(DLQ_POST_DELETED, true);
    }

    @Bean
    public Queue searchUserNicknameChangedDlqQueue() {
        return new Queue(DLQ_USER_NICKNAME_CHANGED, true);
    }

    @Bean
    public Binding searchPostPublishedBinding(@Qualifier("searchPostPublishedQueue") Queue q,
                                             @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(q).to(feedExchange).with(RK_POST_PUBLISHED);
    }

    @Bean
    public Binding searchPostUpdatedBinding(@Qualifier("searchPostUpdatedQueue") Queue q,
                                           @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(q).to(feedExchange).with(RK_POST_UPDATED);
    }

    @Bean
    public Binding searchPostDeletedBinding(@Qualifier("searchPostDeletedQueue") Queue q,
                                           @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(q).to(feedExchange).with(RK_POST_DELETED);
    }

    @Bean
    public Binding searchUserNicknameChangedBinding(@Qualifier("searchUserNicknameChangedQueue") Queue q,
                                                   @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(q).to(feedExchange).with(RK_USER_NICKNAME_CHANGED);
    }

    @Bean
    public Binding searchPostPublishedDlqBinding(@Qualifier("searchPostPublishedDlqQueue") Queue dlq,
                                                @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(dlq).to(feedDlxExchange).with(RK_DLX_POST_PUBLISHED);
    }

    @Bean
    public Binding searchPostUpdatedDlqBinding(@Qualifier("searchPostUpdatedDlqQueue") Queue dlq,
                                              @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(dlq).to(feedDlxExchange).with(RK_DLX_POST_UPDATED);
    }

    @Bean
    public Binding searchPostDeletedDlqBinding(@Qualifier("searchPostDeletedDlqQueue") Queue dlq,
                                              @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(dlq).to(feedDlxExchange).with(RK_DLX_POST_DELETED);
    }

    @Bean
    public Binding searchUserNicknameChangedDlqBinding(@Qualifier("searchUserNicknameChangedDlqQueue") Queue dlq,
                                                       @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(dlq).to(feedDlxExchange).with(RK_DLX_USER_NICKNAME_CHANGED);
    }

    /**
     * Search 索引更新消费专用 ListenerContainerFactory：
     * - 并发/预取可配置（默认 concurrency=2, prefetch=20）
     * - 失败指数退避重试 5 次，耗尽后 reject 进入 DLQ
     */
    @Bean(name = "searchIndexListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory searchIndexListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                                     MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);

        int c = Math.max(1, concurrency);
        factory.setConcurrentConsumers(c);
        factory.setMaxConcurrentConsumers(c);
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
