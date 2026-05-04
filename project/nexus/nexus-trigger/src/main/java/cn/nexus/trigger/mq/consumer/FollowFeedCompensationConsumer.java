package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import cn.nexus.domain.social.service.IFeedFollowCompensationService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Follow event consumer for feed compensation.
 *
 * @author codex
 * @since 2026-05-04
 */
@Component
@RequiredArgsConstructor
public class FollowFeedCompensationConsumer {

    private final IFeedFollowCompensationService compensationService;

    @RabbitListener(queues = RelationCounterRouting.Q_FOLLOW_FEED_COMPENSATE,
            containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FollowFeedCompensationConsumer", eventId = "#event.eventId", payload = "#event")
    public void onMessage(RelationCounterProjectEvent event) {
        if (event == null || event.getSourceId() == null || event.getTargetId() == null) {
            return;
        }
        String status = normalizeStatus(event.getStatus());
        if (status == null) {
            return;
        }
        switch (status) {
            case "ACTIVE":
                compensationService.onFollow(event.getSourceId(), event.getTargetId());
                break;
            case "UNFOLLOW":
                compensationService.onUnfollow(event.getSourceId(), event.getTargetId());
                break;
            default:
                break;
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }
}
