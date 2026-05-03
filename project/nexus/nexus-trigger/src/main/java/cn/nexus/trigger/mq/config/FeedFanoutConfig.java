package cn.nexus.trigger.mq.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Feed 写扩散 MQ 拓扑：发布内容后触发 fanout。
 *
 * @author codex
 * @since 2026-01-12
 */
@Configuration
public class FeedFanoutConfig {

    /**
     * Exchange：Feed 分发事件交换机。
     */
    public static final String EXCHANGE = "social.feed";

    /**
     * Queue：内容发布事件队列。
     */
    public static final String QUEUE = "feed.post.published.queue";

    /**
     * RoutingKey：内容发布事件路由键。
     */
    public static final String ROUTING_KEY = "post.published";

    /**
     * RoutingKey：内容更新事件路由键。
     */
    public static final String RK_POST_UPDATED = "post.updated";

    /**
     * RoutingKey：内容删除事件路由键。
     */
    public static final String RK_POST_DELETED = "post.deleted";

    /**
     * RoutingKey：fanout 切片任务路由键。
     */
    public static final String TASK_ROUTING_KEY = "feed.fanout.task";

    /**
     * Queue：fanout 切片任务队列。
     */
    public static final String TASK_QUEUE = "feed.fanout.task.queue";

    /**
     * DLX：Feed fanout 死信交换机（Direct）。
     */
    public static final String DLX_EXCHANGE = "social.feed.dlx.exchange";

    /**
     * DLQ：内容发布事件死信队列。
     */
    public static final String DLQ_POST_PUBLISHED = "feed.post.published.dlx.queue";

    /**
     * DLQ：fanout 切片任务死信队列。
     */
    public static final String DLQ_FANOUT_TASK = "feed.fanout.task.dlx.queue";

    /**
     * Queue：Feed 索引清理内容更新队列。
     */
    public static final String Q_FEED_INDEX_CLEANUP_UPDATED = "feed.index.cleanup.updated.queue";

    /**
     * Queue：Feed 索引清理内容删除队列。
     */
    public static final String Q_FEED_INDEX_CLEANUP_DELETED = "feed.index.cleanup.deleted.queue";

    /**
     * DLQ：Feed 索引清理内容更新死信队列。
     */
    public static final String DLQ_FEED_INDEX_CLEANUP_UPDATED = "feed.index.cleanup.updated.dlq.queue";

    /**
     * DLQ：Feed 索引清理内容删除死信队列。
     */
    public static final String DLQ_FEED_INDEX_CLEANUP_DELETED = "feed.index.cleanup.deleted.dlq.queue";

    /**
     * DLX RoutingKey：内容发布事件进入死信队列的路由键。
     */
    public static final String DLX_ROUTING_KEY_POST_PUBLISHED = "post.published.dlx";

    /**
     * DLX RoutingKey：fanout 切片任务进入死信队列的路由键。
     */
    public static final String DLX_ROUTING_KEY_FANOUT_TASK = "feed.fanout.task.dlx";

    /**
     * DLX RoutingKey：Feed 索引清理内容更新死信路由键。
     */
    public static final String DLX_ROUTING_KEY_FEED_INDEX_CLEANUP_UPDATED = "feed.index.cleanup.updated.dlx";

    /**
     * DLX RoutingKey：Feed 索引清理内容删除死信路由键。
     */
    public static final String DLX_ROUTING_KEY_FEED_INDEX_CLEANUP_DELETED = "feed.index.cleanup.deleted.dlx";

    /**
     * 统一 MQ 消息序列化为 JSON：避免默认 Java 序列化导致的跨语言/跨版本不稳定。
     *
     * <p>注意：这里故意信任所有 package（trustedPackages="*"），避免本地/多模块下类型映射被拦截。</p>
     *
     * @return JSON MessageConverter {@link MessageConverter}
     */
    @Bean
    public MessageConverter messageConverter(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper.copy().findAndRegisterModules();
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(mapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("*");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    /**
     * 声明 Feed 分发交换机（Direct）。
     *
     * @return exchange
     */
    @Bean
    public DirectExchange feedExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    /**
     * 声明 Feed fanout 死信交换机（Direct）。
     *
     * @return DLX exchange
     */
    @Bean
    public DirectExchange feedDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    /**
     * 声明内容发布队列。
     *
     * @return queue
     */
    @Bean
    public Queue feedPostPublishedQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY_POST_PUBLISHED);
        return new Queue(QUEUE, true, false, false, args);
    }

    /**
     * 声明 fanout 切片任务队列（用于大任务切片并行消费）。
     *
     * @return queue
     */
    @Bean
    public Queue feedFanoutTaskQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY_FANOUT_TASK);
        return new Queue(TASK_QUEUE, true, false, false, args);
    }

    @Bean
    public Queue feedIndexCleanupUpdatedQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY_FEED_INDEX_CLEANUP_UPDATED);
        return new Queue(Q_FEED_INDEX_CLEANUP_UPDATED, true, false, false, args);
    }

    @Bean
    public Queue feedIndexCleanupDeletedQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY_FEED_INDEX_CLEANUP_DELETED);
        return new Queue(Q_FEED_INDEX_CLEANUP_DELETED, true, false, false, args);
    }

    /**
     * 声明内容发布事件死信队列。
     *
     * @return DLQ queue
     */
    @Bean
    public Queue feedPostPublishedDlqQueue() {
        return new Queue(DLQ_POST_PUBLISHED, true);
    }

    /**
     * 声明 fanout 切片任务死信队列。
     *
     * @return DLQ queue
     */
    @Bean
    public Queue feedFanoutTaskDlqQueue() {
        return new Queue(DLQ_FANOUT_TASK, true);
    }

    @Bean
    public Queue feedIndexCleanupUpdatedDlqQueue() {
        return new Queue(DLQ_FEED_INDEX_CLEANUP_UPDATED, true);
    }

    @Bean
    public Queue feedIndexCleanupDeletedDlqQueue() {
        return new Queue(DLQ_FEED_INDEX_CLEANUP_DELETED, true);
    }

    /**
     * 声明交换机与队列的绑定关系。
     *
     * @param feedPostPublishedQueue 队列
     * @param feedExchange           交换机
     * @return binding
     */
    @Bean
    public Binding feedPostPublishedBinding(@Qualifier("feedPostPublishedQueue") Queue feedPostPublishedQueue,
                                            @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(feedPostPublishedQueue).to(feedExchange).with(ROUTING_KEY);
    }

    /**
     * 声明交换机与 fanout 切片任务队列的绑定关系。
     *
     * @param feedFanoutTaskQueue fanout 切片任务队列
     * @param feedExchange        交换机
     * @return binding
     */
    @Bean
    public Binding feedFanoutTaskBinding(@Qualifier("feedFanoutTaskQueue") Queue feedFanoutTaskQueue,
                                         @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(feedFanoutTaskQueue).to(feedExchange).with(TASK_ROUTING_KEY);
    }

    @Bean
    public Binding feedIndexCleanupUpdatedBinding(@Qualifier("feedIndexCleanupUpdatedQueue") Queue feedIndexCleanupUpdatedQueue,
                                                  @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(feedIndexCleanupUpdatedQueue).to(feedExchange).with(RK_POST_UPDATED);
    }

    @Bean
    public Binding feedIndexCleanupDeletedBinding(@Qualifier("feedIndexCleanupDeletedQueue") Queue feedIndexCleanupDeletedQueue,
                                                  @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(feedIndexCleanupDeletedQueue).to(feedExchange).with(RK_POST_DELETED);
    }

    /**
     * 声明死信交换机与“内容发布事件死信队列”的绑定关系。
     *
     * @param feedPostPublishedDlqQueue 内容发布事件死信队列
     * @param feedDlxExchange           死信交换机
     * @return binding
     */
    @Bean
    public Binding feedPostPublishedDlqBinding(@Qualifier("feedPostPublishedDlqQueue") Queue feedPostPublishedDlqQueue,
                                               @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(feedPostPublishedDlqQueue).to(feedDlxExchange).with(DLX_ROUTING_KEY_POST_PUBLISHED);
    }

    /**
     * 声明死信交换机与“fanout 切片任务死信队列”的绑定关系。
     *
     * @param feedFanoutTaskDlqQueue fanout 切片任务死信队列
     * @param feedDlxExchange        死信交换机
     * @return binding
     */
    @Bean
    public Binding feedFanoutTaskDlqBinding(@Qualifier("feedFanoutTaskDlqQueue") Queue feedFanoutTaskDlqQueue,
                                            @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(feedFanoutTaskDlqQueue).to(feedDlxExchange).with(DLX_ROUTING_KEY_FANOUT_TASK);
    }

    @Bean
    public Binding feedIndexCleanupUpdatedDlqBinding(@Qualifier("feedIndexCleanupUpdatedDlqQueue") Queue feedIndexCleanupUpdatedDlqQueue,
                                                     @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(feedIndexCleanupUpdatedDlqQueue)
                .to(feedDlxExchange)
                .with(DLX_ROUTING_KEY_FEED_INDEX_CLEANUP_UPDATED);
    }

    @Bean
    public Binding feedIndexCleanupDeletedDlqBinding(@Qualifier("feedIndexCleanupDeletedDlqQueue") Queue feedIndexCleanupDeletedDlqQueue,
                                                     @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(feedIndexCleanupDeletedDlqQueue)
                .to(feedDlxExchange)
                .with(DLX_ROUTING_KEY_FEED_INDEX_CLEANUP_DELETED);
    }
}
