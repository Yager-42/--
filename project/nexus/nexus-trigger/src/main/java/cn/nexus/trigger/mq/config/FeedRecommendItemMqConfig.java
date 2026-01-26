package cn.nexus.trigger.mq.config;

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
 * 推荐 Item 写入 MQ 拓扑：消费 {@code post.published}，将内容写入推荐系统（upsertItem）。
 *
 * <p>注意：必须使用独立队列 {@code feed.recommend.item.upsert.queue}，不能与 fanout 消费共用。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Configuration
public class FeedRecommendItemMqConfig {

    /** Queue：推荐 item upsert 队列（独立）。 */
    public static final String Q_FEED_RECOMMEND_ITEM_UPSERT = "feed.recommend.item.upsert.queue";

    /** DLQ：推荐 item upsert 死信队列。 */
    public static final String DLQ_FEED_RECOMMEND_ITEM_UPSERT = "feed.recommend.item.upsert.dlq.queue";

    /** DLX RoutingKey：进入推荐 item upsert DLQ 的路由键。 */
    public static final String RK_FEED_RECOMMEND_ITEM_UPSERT_DLX = "feed.recommend.item.upsert.dlx";

    /** RoutingKey：内容删除事件。 */
    public static final String RK_POST_DELETED = "post.deleted";

    /** Queue：推荐 item delete 队列（独立）。 */
    public static final String Q_FEED_RECOMMEND_ITEM_DELETE = "feed.recommend.item.delete.queue";

    /** DLQ：推荐 item delete 死信队列。 */
    public static final String DLQ_FEED_RECOMMEND_ITEM_DELETE = "feed.recommend.item.delete.dlq.queue";

    /** DLX RoutingKey：进入推荐 item delete DLQ 的路由键。 */
    public static final String RK_FEED_RECOMMEND_ITEM_DELETE_DLX = "feed.recommend.item.delete.dlx";

    @Bean
    public Queue feedRecommendItemUpsertQueue() {
        Map<String, Object> args = new HashMap<>();
        // 复用 feed DLX：保证消费失败不“直接丢”。
        args.put("x-dead-letter-exchange", FeedFanoutConfig.DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", RK_FEED_RECOMMEND_ITEM_UPSERT_DLX);
        return new Queue(Q_FEED_RECOMMEND_ITEM_UPSERT, true, false, false, args);
    }

    @Bean
    public Queue feedRecommendItemUpsertDlqQueue() {
        return new Queue(DLQ_FEED_RECOMMEND_ITEM_UPSERT, true);
    }

    @Bean
    public Binding feedRecommendItemUpsertDlqBinding(@Qualifier("feedRecommendItemUpsertDlqQueue") Queue feedRecommendItemUpsertDlqQueue,
                                                    @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(feedRecommendItemUpsertDlqQueue).to(feedDlxExchange).with(RK_FEED_RECOMMEND_ITEM_UPSERT_DLX);
    }

    @Bean
    public Binding feedRecommendItemUpsertBinding(@Qualifier("feedRecommendItemUpsertQueue") Queue feedRecommendItemUpsertQueue,
                                                  @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(feedRecommendItemUpsertQueue).to(feedExchange).with(FeedFanoutConfig.ROUTING_KEY);
    }

    @Bean
    public Queue feedRecommendItemDeleteQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", FeedFanoutConfig.DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", RK_FEED_RECOMMEND_ITEM_DELETE_DLX);
        return new Queue(Q_FEED_RECOMMEND_ITEM_DELETE, true, false, false, args);
    }

    @Bean
    public Queue feedRecommendItemDeleteDlqQueue() {
        return new Queue(DLQ_FEED_RECOMMEND_ITEM_DELETE, true);
    }

    @Bean
    public Binding feedRecommendItemDeleteDlqBinding(@Qualifier("feedRecommendItemDeleteDlqQueue") Queue feedRecommendItemDeleteDlqQueue,
                                                     @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(feedRecommendItemDeleteDlqQueue).to(feedDlxExchange).with(RK_FEED_RECOMMEND_ITEM_DELETE_DLX);
    }

    @Bean
    public Binding feedRecommendItemDeleteBinding(@Qualifier("feedRecommendItemDeleteQueue") Queue feedRecommendItemDeleteQueue,
                                                  @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(feedRecommendItemDeleteQueue).to(feedExchange).with(RK_POST_DELETED);
    }
}
