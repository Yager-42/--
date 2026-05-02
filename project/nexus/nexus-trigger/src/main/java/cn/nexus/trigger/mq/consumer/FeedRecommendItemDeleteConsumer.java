package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.FeedRecommendItemMqConfig;
import cn.nexus.types.event.PostDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 推荐 Item 删除消费者：PostDeletedEvent -> deleteItem。
 *
 * @author rr
 * @author codex
 * @since 2026-01-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRecommendItemDeleteConsumer {

    private final IRecommendationPort recommendationPort;

    /**
     * 消费删帖事件并同步删除推荐系统里的 Item。
     *
     * @param event 删帖事件。 {@link PostDeletedEvent}
     */
    @RabbitListener(queues = FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_DELETE, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedRecommendItemDeleteConsumer", eventId = "#event.eventId", payload = "#event")
    public void onMessage(PostDeletedEvent event) {
        if (event == null || event.getPostId() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("recommend item delete payload invalid");
        }
        recommendationPort.deleteItem(event.getPostId());
    }
}
