package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.service.IFeedDistributionService;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.event.PostPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Feed fanout 消费者：接收 PostPublishedEvent 并委托领域层执行写扩散。
 *
 * @author codex
 * @since 2026-01-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedFanoutConsumer {

    private final IFeedDistributionService feedDistributionService;

    /**
     * 消费内容发布事件，触发 fanout。
     *
     * @param event 发布事件
     */
    @RabbitListener(queues = FeedFanoutConfig.QUEUE)
    public void onMessage(PostPublishedEvent event) {
        try {
            feedDistributionService.fanout(event);
        } catch (Exception e) {
            log.error("MQ feed fanout failed, postId={}, authorId={}", event.getPostId(), event.getAuthorId(), e);
            throw new AmqpRejectAndDontRequeueException("feed fanout failed", e);
        }
    }
}

