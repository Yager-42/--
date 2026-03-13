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
 * Search 索引更新 `MQ` 拓扑配置。
 *
 * <p>负责把 `social.feed` 上的内容事件和用户昵称事件绑定到独立队列，并统一挂上 `DLQ` 与消费重试策略。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-02
 */
@Configuration
public class SearchIndexMqConfig {

    /** 帖子发布事件队列名。 */
    public static final String Q_POST_PUBLISHED = "search.post.published.queue";
    /** 帖子更新事件队列名。 */
    public static final String Q_POST_UPDATED = "search.post.updated.queue";
    /** 帖子删除事件队列名。 */
    public static final String Q_POST_DELETED = "search.post.deleted.queue";
    /** 用户昵称变更事件队列名。 */
    public static final String Q_USER_NICKNAME_CHANGED = "search.user.nickname_changed.queue";

    /** 帖子发布事件死信队列名。 */
    public static final String DLQ_POST_PUBLISHED = "search.post.published.dlx.queue";
    /** 帖子更新事件死信队列名。 */
    public static final String DLQ_POST_UPDATED = "search.post.updated.dlx.queue";
    /** 帖子删除事件死信队列名。 */
    public static final String DLQ_POST_DELETED = "search.post.deleted.dlx.queue";
    /** 用户昵称变更事件死信队列名。 */
    public static final String DLQ_USER_NICKNAME_CHANGED = "search.user.nickname_changed.dlx.queue";

    /** 帖子发布事件路由键。 */
    public static final String RK_POST_PUBLISHED = "post.published";
    /** 帖子更新事件路由键。 */
    public static final String RK_POST_UPDATED = "post.updated";
    /** 帖子删除事件路由键。 */
    public static final String RK_POST_DELETED = "post.deleted";
    /** 用户昵称变更事件路由键。 */
    public static final String RK_USER_NICKNAME_CHANGED = "user.nickname_changed";

    /** 帖子发布事件死信路由键。 */
    public static final String RK_DLX_POST_PUBLISHED = "search.post.published.dlx";
    /** 帖子更新事件死信路由键。 */
    public static final String RK_DLX_POST_UPDATED = "search.post.updated.dlx";
    /** 帖子删除事件死信路由键。 */
    public static final String RK_DLX_POST_DELETED = "search.post.deleted.dlx";
    /** 用户昵称变更事件死信路由键。 */
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

    /**
     * 创建帖子发布事件消费队列。
     *
     * @return 带死信配置的消费队列，类型：{@link Queue}
     */
    @Bean
    public Queue searchPostPublishedQueue() {
        return queueWithDlx(Q_POST_PUBLISHED, RK_DLX_POST_PUBLISHED);
    }

    /**
     * 创建帖子更新事件消费队列。
     *
     * @return 带死信配置的消费队列，类型：{@link Queue}
     */
    @Bean
    public Queue searchPostUpdatedQueue() {
        return queueWithDlx(Q_POST_UPDATED, RK_DLX_POST_UPDATED);
    }

    /**
     * 创建帖子删除事件消费队列。
     *
     * @return 带死信配置的消费队列，类型：{@link Queue}
     */
    @Bean
    public Queue searchPostDeletedQueue() {
        return queueWithDlx(Q_POST_DELETED, RK_DLX_POST_DELETED);
    }

    /**
     * 创建用户昵称变更事件消费队列。
     *
     * @return 带死信配置的消费队列，类型：{@link Queue}
     */
    @Bean
    public Queue searchUserNicknameChangedQueue() {
        return queueWithDlx(Q_USER_NICKNAME_CHANGED, RK_DLX_USER_NICKNAME_CHANGED);
    }

    /**
     * 创建帖子发布事件死信队列。
     *
     * @return 可持久化的死信队列，类型：{@link Queue}
     */
    @Bean
    public Queue searchPostPublishedDlqQueue() {
        return new Queue(DLQ_POST_PUBLISHED, true);
    }

    /**
     * 创建帖子更新事件死信队列。
     *
     * @return 可持久化的死信队列，类型：{@link Queue}
     */
    @Bean
    public Queue searchPostUpdatedDlqQueue() {
        return new Queue(DLQ_POST_UPDATED, true);
    }

    /**
     * 创建帖子删除事件死信队列。
     *
     * @return 可持久化的死信队列，类型：{@link Queue}
     */
    @Bean
    public Queue searchPostDeletedDlqQueue() {
        return new Queue(DLQ_POST_DELETED, true);
    }

    /**
     * 创建用户昵称变更事件死信队列。
     *
     * @return 可持久化的死信队列，类型：{@link Queue}
     */
    @Bean
    public Queue searchUserNicknameChangedDlqQueue() {
        return new Queue(DLQ_USER_NICKNAME_CHANGED, true);
    }

    /**
     * 绑定帖子发布事件队列到主交换机。
     *
     * @param q 帖子发布事件队列，类型：{@link Queue}
     * @param feedExchange 主业务交换机，类型：{@link DirectExchange}
     * @return 队列与路由键的绑定关系，类型：{@link Binding}
     */
    @Bean
    public Binding searchPostPublishedBinding(@Qualifier("searchPostPublishedQueue") Queue q,
                                             @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(q).to(feedExchange).with(RK_POST_PUBLISHED);
    }

    /**
     * 绑定帖子更新事件队列到主交换机。
     *
     * @param q 帖子更新事件队列，类型：{@link Queue}
     * @param feedExchange 主业务交换机，类型：{@link DirectExchange}
     * @return 队列与路由键的绑定关系，类型：{@link Binding}
     */
    @Bean
    public Binding searchPostUpdatedBinding(@Qualifier("searchPostUpdatedQueue") Queue q,
                                           @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(q).to(feedExchange).with(RK_POST_UPDATED);
    }

    /**
     * 绑定帖子删除事件队列到主交换机。
     *
     * @param q 帖子删除事件队列，类型：{@link Queue}
     * @param feedExchange 主业务交换机，类型：{@link DirectExchange}
     * @return 队列与路由键的绑定关系，类型：{@link Binding}
     */
    @Bean
    public Binding searchPostDeletedBinding(@Qualifier("searchPostDeletedQueue") Queue q,
                                           @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(q).to(feedExchange).with(RK_POST_DELETED);
    }

    /**
     * 绑定用户昵称变更事件队列到主交换机。
     *
     * @param q 用户昵称变更事件队列，类型：{@link Queue}
     * @param feedExchange 主业务交换机，类型：{@link DirectExchange}
     * @return 队列与路由键的绑定关系，类型：{@link Binding}
     */
    @Bean
    public Binding searchUserNicknameChangedBinding(@Qualifier("searchUserNicknameChangedQueue") Queue q,
                                                   @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(q).to(feedExchange).with(RK_USER_NICKNAME_CHANGED);
    }

    /**
     * 绑定帖子发布事件死信队列到死信交换机。
     *
     * @param dlq 帖子发布事件死信队列，类型：{@link Queue}
     * @param feedDlxExchange 死信交换机，类型：{@link DirectExchange}
     * @return 队列与路由键的绑定关系，类型：{@link Binding}
     */
    @Bean
    public Binding searchPostPublishedDlqBinding(@Qualifier("searchPostPublishedDlqQueue") Queue dlq,
                                                @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(dlq).to(feedDlxExchange).with(RK_DLX_POST_PUBLISHED);
    }

    /**
     * 绑定帖子更新事件死信队列到死信交换机。
     *
     * @param dlq 帖子更新事件死信队列，类型：{@link Queue}
     * @param feedDlxExchange 死信交换机，类型：{@link DirectExchange}
     * @return 队列与路由键的绑定关系，类型：{@link Binding}
     */
    @Bean
    public Binding searchPostUpdatedDlqBinding(@Qualifier("searchPostUpdatedDlqQueue") Queue dlq,
                                              @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(dlq).to(feedDlxExchange).with(RK_DLX_POST_UPDATED);
    }

    /**
     * 绑定帖子删除事件死信队列到死信交换机。
     *
     * @param dlq 帖子删除事件死信队列，类型：{@link Queue}
     * @param feedDlxExchange 死信交换机，类型：{@link DirectExchange}
     * @return 队列与路由键的绑定关系，类型：{@link Binding}
     */
    @Bean
    public Binding searchPostDeletedDlqBinding(@Qualifier("searchPostDeletedDlqQueue") Queue dlq,
                                              @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(dlq).to(feedDlxExchange).with(RK_DLX_POST_DELETED);
    }

    /**
     * 绑定用户昵称变更事件死信队列到死信交换机。
     *
     * @param dlq 用户昵称变更事件死信队列，类型：{@link Queue}
     * @param feedDlxExchange 死信交换机，类型：{@link DirectExchange}
     * @return 队列与路由键的绑定关系，类型：{@link Binding}
     */
    @Bean
    public Binding searchUserNicknameChangedDlqBinding(@Qualifier("searchUserNicknameChangedDlqQueue") Queue dlq,
                                                       @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(dlq).to(feedDlxExchange).with(RK_DLX_USER_NICKNAME_CHANGED);
    }

    /**
     * 创建 Search 索引消费专用的监听器工厂。
     *
     * <p>这里统一收口并发度、预取值和指数退避重试，避免每个消费者各自散落配置。</p>
     *
     * @param connectionFactory RabbitMQ 连接工厂，类型：{@link ConnectionFactory}
     * @param messageConverter 消息编解码器，类型：{@link MessageConverter}
     * @return 监听器工厂，类型：{@link SimpleRabbitListenerContainerFactory}
     */
    @Bean(name = "searchIndexListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory searchIndexListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                                     MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);

        // Search 索引更新是异步链路，消费并发固定即可，避免过度抢占连接。
        int c = Math.max(1, concurrency);
        factory.setConcurrentConsumers(c);
        factory.setMaxConcurrentConsumers(c);
        factory.setPrefetchCount(Math.max(1, prefetch));
        factory.setDefaultRequeueRejected(false);

        // 失败先做本地重试；重试耗尽后直接 reject，让消息按死信路由进入 `DLQ`。
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
