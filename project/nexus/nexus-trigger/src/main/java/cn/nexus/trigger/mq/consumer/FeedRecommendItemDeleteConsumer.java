package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.trigger.mq.config.FeedRecommendItemMqConfig;
import cn.nexus.types.event.PostDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 推荐 Item 删除消费者：PostDeletedEvent -> deleteItem。
 *
 * <p>best-effort：失败只打日志，不阻断主链路。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedRecommendItemDeleteConsumer {

    private final IRecommendationPort recommendationPort;

    @RabbitListener(queues = FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_DELETE)
    public void onMessage(PostDeletedEvent event) {
        if (event == null || event.getPostId() == null) {
            return;
        }
        try {
            recommendationPort.deleteItem(event.getPostId());
        } catch (Exception e) {
            log.warn("recommend item delete failed, eventId={}, postId={}, operatorId={}",
                    event.getEventId(), event.getPostId(), event.getOperatorId(), e);
        }
    }
}

