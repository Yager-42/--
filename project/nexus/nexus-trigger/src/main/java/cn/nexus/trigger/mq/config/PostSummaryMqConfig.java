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
 * 内容摘要生成 MQ 拓扑：消费 {@code post.summary.generate}，异步生成并写回 summary。
 */
@Configuration
public class PostSummaryMqConfig {

    /** RoutingKey：摘要生成事件。 */
    public static final String RK_POST_SUMMARY_GENERATE = "post.summary.generate";

    /** Queue：摘要生成队列。 */
    public static final String Q_POST_SUMMARY_GENERATE = "content.post.summary.generate.queue";

    /** DLQ：摘要生成死信队列。 */
    public static final String DLQ_POST_SUMMARY_GENERATE = "content.post.summary.generate.dlx.queue";

    /** DLX RoutingKey：进入摘要生成 DLQ 的路由键。 */
    public static final String RK_POST_SUMMARY_GENERATE_DLX = "post.summary.generate.dlx";

    @Bean
    public Queue postSummaryGenerateQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", FeedFanoutConfig.DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", RK_POST_SUMMARY_GENERATE_DLX);
        return new Queue(Q_POST_SUMMARY_GENERATE, true, false, false, args);
    }

    @Bean
    public Queue postSummaryGenerateDlqQueue() {
        return new Queue(DLQ_POST_SUMMARY_GENERATE, true);
    }

    @Bean
    public Binding postSummaryGenerateDlqBinding(@Qualifier("postSummaryGenerateDlqQueue") Queue postSummaryGenerateDlqQueue,
                                                 @Qualifier("feedDlxExchange") DirectExchange feedDlxExchange) {
        return BindingBuilder.bind(postSummaryGenerateDlqQueue).to(feedDlxExchange).with(RK_POST_SUMMARY_GENERATE_DLX);
    }

    @Bean
    public Binding postSummaryGenerateBinding(@Qualifier("postSummaryGenerateQueue") Queue postSummaryGenerateQueue,
                                              @Qualifier("feedExchange") DirectExchange feedExchange) {
        return BindingBuilder.bind(postSummaryGenerateQueue).to(feedExchange).with(RK_POST_SUMMARY_GENERATE);
    }
}

