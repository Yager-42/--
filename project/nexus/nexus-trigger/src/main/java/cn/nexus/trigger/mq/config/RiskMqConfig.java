package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 风控异步链路 MQ 拓扑：LLM 扫描、图片扫描、人审工单等任务队列。
 *
 * <p>设计原则：RabbitMQ 用作工作队列，明确 ACK/重试/DLQ 语义。</p>
 */
@Configuration
public class RiskMqConfig {

    public static final String EXCHANGE = "social.risk";

    public static final String RK_LLM_SCAN = "risk.llm.scan";
    public static final String RK_IMAGE_SCAN = "risk.image.scan";
    public static final String RK_REVIEW_CASE = "risk.review.case";
    /** 扫描完成事件（旁路消费用，可不绑定队列） */
    public static final String RK_SCAN_COMPLETED = "risk.scan.completed";

    public static final String Q_LLM_SCAN = "risk.llm.scan.queue";
    public static final String Q_IMAGE_SCAN = "risk.image.scan.queue";
    public static final String Q_REVIEW_CASE = "risk.review.case.queue";

    public static final String DLX_EXCHANGE = "social.risk.dlx.exchange";
    public static final String DLQ_LLM_SCAN = "risk.llm.scan.dlq";
    public static final String DLQ_IMAGE_SCAN = "risk.image.scan.dlq";
    public static final String DLQ_REVIEW_CASE = "risk.review.case.dlq";
    public static final String DLX_RK_LLM_SCAN = "risk.llm.scan.dlx";
    public static final String DLX_RK_IMAGE_SCAN = "risk.image.scan.dlx";
    public static final String DLX_RK_REVIEW_CASE = "risk.review.case.dlx";

    @Bean
    public DirectExchange riskExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange riskDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue riskLlmScanQueue() {
        return new Queue(Q_LLM_SCAN, true, false, false, dlxArgs(DLX_RK_LLM_SCAN));
    }

    @Bean
    public Queue riskImageScanQueue() {
        return new Queue(Q_IMAGE_SCAN, true, false, false, dlxArgs(DLX_RK_IMAGE_SCAN));
    }

    @Bean
    public Queue riskReviewCaseQueue() {
        return new Queue(Q_REVIEW_CASE, true, false, false, dlxArgs(DLX_RK_REVIEW_CASE));
    }

    @Bean
    public Queue riskLlmScanDlqQueue() {
        return new Queue(DLQ_LLM_SCAN, true);
    }

    @Bean
    public Queue riskImageScanDlqQueue() {
        return new Queue(DLQ_IMAGE_SCAN, true);
    }

    @Bean
    public Queue riskReviewCaseDlqQueue() {
        return new Queue(DLQ_REVIEW_CASE, true);
    }

    @Bean
    public Binding bindRiskLlmScan(Queue riskLlmScanQueue, DirectExchange riskExchange) {
        return BindingBuilder.bind(riskLlmScanQueue).to(riskExchange).with(RK_LLM_SCAN);
    }

    @Bean
    public Binding bindRiskImageScan(Queue riskImageScanQueue, DirectExchange riskExchange) {
        return BindingBuilder.bind(riskImageScanQueue).to(riskExchange).with(RK_IMAGE_SCAN);
    }

    @Bean
    public Binding bindRiskReviewCase(Queue riskReviewCaseQueue, DirectExchange riskExchange) {
        return BindingBuilder.bind(riskReviewCaseQueue).to(riskExchange).with(RK_REVIEW_CASE);
    }

    @Bean
    public Binding bindRiskLlmScanDlq(Queue riskLlmScanDlqQueue, DirectExchange riskDlxExchange) {
        return BindingBuilder.bind(riskLlmScanDlqQueue).to(riskDlxExchange).with(DLX_RK_LLM_SCAN);
    }

    @Bean
    public Binding bindRiskImageScanDlq(Queue riskImageScanDlqQueue, DirectExchange riskDlxExchange) {
        return BindingBuilder.bind(riskImageScanDlqQueue).to(riskDlxExchange).with(DLX_RK_IMAGE_SCAN);
    }

    @Bean
    public Binding bindRiskReviewCaseDlq(Queue riskReviewCaseDlqQueue, DirectExchange riskDlxExchange) {
        return BindingBuilder.bind(riskReviewCaseDlqQueue).to(riskDlxExchange).with(DLX_RK_REVIEW_CASE);
    }

    private Map<String, Object> dlxArgs(String dlxRoutingKey) {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", dlxRoutingKey);
        return args;
    }
}
