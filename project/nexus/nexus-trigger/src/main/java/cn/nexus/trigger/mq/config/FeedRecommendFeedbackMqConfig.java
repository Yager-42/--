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
 * 推荐反馈 MQ 拓扑（C 通道）：消费 RecommendFeedbackEvent 写入推荐系统。
 *
 * <p>独立 exchange/queue，避免与通知链路抢消息。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Configuration
public class FeedRecommendFeedbackMqConfig {

    public static final String EXCHANGE = "social.recommend";
    public static final String RK_RECOMMEND_FEEDBACK = "recommend.feedback";
    public static final String QUEUE = "feed.recommend.feedback.queue";

    /** 死信交换机（Direct）：消费失败必须能落地，不允许“直接丢”。 */
    public static final String DLX_EXCHANGE = "social.recommend.dlx";
    public static final String DLQ_RECOMMEND_FEEDBACK = "feed.recommend.feedback.dlq.queue";
    public static final String RK_RECOMMEND_FEEDBACK_DLX = "recommend.feedback.dlx";

    @Bean
    public DirectExchange recommendExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange recommendDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue recommendFeedbackQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", RK_RECOMMEND_FEEDBACK_DLX);
        return new Queue(QUEUE, true, false, false, args);
    }

    @Bean
    public Queue recommendFeedbackDlqQueue() {
        return new Queue(DLQ_RECOMMEND_FEEDBACK, true);
    }

    @Bean
    public Binding recommendFeedbackDlqBinding(@Qualifier("recommendFeedbackDlqQueue") Queue recommendFeedbackDlqQueue,
                                             @Qualifier("recommendDlxExchange") DirectExchange recommendDlxExchange) {
        return BindingBuilder.bind(recommendFeedbackDlqQueue).to(recommendDlxExchange).with(RK_RECOMMEND_FEEDBACK_DLX);
    }

    @Bean
    public Binding recommendFeedbackBinding(@Qualifier("recommendFeedbackQueue") Queue recommendFeedbackQueue,
                                           @Qualifier("recommendExchange") DirectExchange recommendExchange) {
        return BindingBuilder.bind(recommendFeedbackQueue).to(recommendExchange).with(RK_RECOMMEND_FEEDBACK);
    }
}

