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
 * @author rr
 * @author codex
 * @since 2026-01-29
 */
@Configuration
public class RiskMqConfig {

    /**
     * EXCHANGE 字段。
     */
    public static final String EXCHANGE = "social.risk";

    /**
     * RK_LLM_SCAN 字段。
     */
    public static final String RK_LLM_SCAN = "risk.llm.scan";
    /**
     * RK_IMAGE_SCAN 字段。
     */
    public static final String RK_IMAGE_SCAN = "risk.image.scan";
    /**
     * RK_REVIEW_CASE 字段。
     */
    public static final String RK_REVIEW_CASE = "risk.review.case";
    /** 扫描完成事件（旁路消费用，可不绑定队列） */
    public static final String RK_SCAN_COMPLETED = "risk.scan.completed";

    /**
     * Q_LLM_SCAN 字段。
     */
    public static final String Q_LLM_SCAN = "risk.llm.scan.queue";
    /**
     * Q_IMAGE_SCAN 字段。
     */
    public static final String Q_IMAGE_SCAN = "risk.image.scan.queue";
    /**
     * Q_REVIEW_CASE 字段。
     */
    public static final String Q_REVIEW_CASE = "risk.review.case.queue";

    /**
     * DLX_EXCHANGE 字段。
     */
    public static final String DLX_EXCHANGE = "social.risk.dlx.exchange";
    /**
     * DLQ_LLM_SCAN 字段。
     */
    public static final String DLQ_LLM_SCAN = "risk.llm.scan.dlq";
    /**
     * DLQ_IMAGE_SCAN 字段。
     */
    public static final String DLQ_IMAGE_SCAN = "risk.image.scan.dlq";
    /**
     * DLQ_REVIEW_CASE 字段。
     */
    public static final String DLQ_REVIEW_CASE = "risk.review.case.dlq";
    /**
     * DLX_RK_LLM_SCAN 字段。
     */
    public static final String DLX_RK_LLM_SCAN = "risk.llm.scan.dlx";
    /**
     * DLX_RK_IMAGE_SCAN 字段。
     */
    public static final String DLX_RK_IMAGE_SCAN = "risk.image.scan.dlx";
    /**
     * DLX_RK_REVIEW_CASE 字段。
     */
    public static final String DLX_RK_REVIEW_CASE = "risk.review.case.dlx";

    /**
     * 执行 riskExchange 逻辑。
     *
     * @return 处理结果。类型：{@link DirectExchange}
     */
    @Bean
    public DirectExchange riskExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    /**
     * 执行 riskDlxExchange 逻辑。
     *
     * @return 处理结果。类型：{@link DirectExchange}
     */
    @Bean
    public DirectExchange riskDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    /**
     * 执行 riskLlmScanQueue 逻辑。
     *
     * @return 处理结果。类型：{@link Queue}
     */
    @Bean
    public Queue riskLlmScanQueue() {
        return new Queue(Q_LLM_SCAN, true, false, false, dlxArgs(DLX_RK_LLM_SCAN));
    }

    /**
     * 执行 riskImageScanQueue 逻辑。
     *
     * @return 处理结果。类型：{@link Queue}
     */
    @Bean
    public Queue riskImageScanQueue() {
        return new Queue(Q_IMAGE_SCAN, true, false, false, dlxArgs(DLX_RK_IMAGE_SCAN));
    }

    /**
     * 执行 riskReviewCaseQueue 逻辑。
     *
     * @return 处理结果。类型：{@link Queue}
     */
    @Bean
    public Queue riskReviewCaseQueue() {
        return new Queue(Q_REVIEW_CASE, true, false, false, dlxArgs(DLX_RK_REVIEW_CASE));
    }

    /**
     * 执行 riskLlmScanDlqQueue 逻辑。
     *
     * @return 处理结果。类型：{@link Queue}
     */
    @Bean
    public Queue riskLlmScanDlqQueue() {
        return new Queue(DLQ_LLM_SCAN, true);
    }

    /**
     * 执行 riskImageScanDlqQueue 逻辑。
     *
     * @return 处理结果。类型：{@link Queue}
     */
    @Bean
    public Queue riskImageScanDlqQueue() {
        return new Queue(DLQ_IMAGE_SCAN, true);
    }

    /**
     * 执行 riskReviewCaseDlqQueue 逻辑。
     *
     * @return 处理结果。类型：{@link Queue}
     */
    @Bean
    public Queue riskReviewCaseDlqQueue() {
        return new Queue(DLQ_REVIEW_CASE, true);
    }

    /**
     * 执行 bindRiskLlmScan 逻辑。
     *
     * @param riskLlmScanQueue riskLlmScanQueue 参数。类型：{@link Queue}
     * @param riskExchange riskExchange 参数。类型：{@link DirectExchange}
     * @return 处理结果。类型：{@link Binding}
     */
    @Bean
    public Binding bindRiskLlmScan(Queue riskLlmScanQueue, DirectExchange riskExchange) {
        return BindingBuilder.bind(riskLlmScanQueue).to(riskExchange).with(RK_LLM_SCAN);
    }

    /**
     * 执行 bindRiskImageScan 逻辑。
     *
     * @param riskImageScanQueue riskImageScanQueue 参数。类型：{@link Queue}
     * @param riskExchange riskExchange 参数。类型：{@link DirectExchange}
     * @return 处理结果。类型：{@link Binding}
     */
    @Bean
    public Binding bindRiskImageScan(Queue riskImageScanQueue, DirectExchange riskExchange) {
        return BindingBuilder.bind(riskImageScanQueue).to(riskExchange).with(RK_IMAGE_SCAN);
    }

    /**
     * 执行 bindRiskReviewCase 逻辑。
     *
     * @param riskReviewCaseQueue riskReviewCaseQueue 参数。类型：{@link Queue}
     * @param riskExchange riskExchange 参数。类型：{@link DirectExchange}
     * @return 处理结果。类型：{@link Binding}
     */
    @Bean
    public Binding bindRiskReviewCase(Queue riskReviewCaseQueue, DirectExchange riskExchange) {
        return BindingBuilder.bind(riskReviewCaseQueue).to(riskExchange).with(RK_REVIEW_CASE);
    }

    /**
     * 执行 bindRiskLlmScanDlq 逻辑。
     *
     * @param riskLlmScanDlqQueue riskLlmScanDlqQueue 参数。类型：{@link Queue}
     * @param riskDlxExchange riskDlxExchange 参数。类型：{@link DirectExchange}
     * @return 处理结果。类型：{@link Binding}
     */
    @Bean
    public Binding bindRiskLlmScanDlq(Queue riskLlmScanDlqQueue, DirectExchange riskDlxExchange) {
        return BindingBuilder.bind(riskLlmScanDlqQueue).to(riskDlxExchange).with(DLX_RK_LLM_SCAN);
    }

    /**
     * 执行 bindRiskImageScanDlq 逻辑。
     *
     * @param riskImageScanDlqQueue riskImageScanDlqQueue 参数。类型：{@link Queue}
     * @param riskDlxExchange riskDlxExchange 参数。类型：{@link DirectExchange}
     * @return 处理结果。类型：{@link Binding}
     */
    @Bean
    public Binding bindRiskImageScanDlq(Queue riskImageScanDlqQueue, DirectExchange riskDlxExchange) {
        return BindingBuilder.bind(riskImageScanDlqQueue).to(riskDlxExchange).with(DLX_RK_IMAGE_SCAN);
    }

    /**
     * 执行 bindRiskReviewCaseDlq 逻辑。
     *
     * @param riskReviewCaseDlqQueue riskReviewCaseDlqQueue 参数。类型：{@link Queue}
     * @param riskDlxExchange riskDlxExchange 参数。类型：{@link DirectExchange}
     * @return 处理结果。类型：{@link Binding}
     */
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
