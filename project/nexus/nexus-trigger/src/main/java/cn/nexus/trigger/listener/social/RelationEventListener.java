package cn.nexus.trigger.listener.social;

import cn.nexus.domain.social.model.valobj.NotificationListVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.service.IFeedService;
import cn.nexus.domain.social.service.IInteractionService;
import cn.nexus.domain.social.service.IRiskService;
import cn.nexus.domain.social.adapter.port.IRelationEventInboxPort;
import cn.nexus.infrastructure.adapter.social.port.RelationBlockEvent;
import cn.nexus.infrastructure.adapter.social.port.RelationFollowEvent;
import cn.nexus.infrastructure.adapter.social.port.RelationFriendEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 关系事件监听，消费 MQ 并触达 Feed/通知/风控/IM（通知占位）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RelationEventListener {

    private final IFeedService feedService;
    private final IInteractionService interactionService;
    private final IRiskService riskService;
    private final IRelationEventInboxPort relationEventInboxPort;

    @Async
    @EventListener
    public void onFollow(RelationFollowEvent event) {
        handleFollow(event);
    }

    @Async
    @EventListener
    public void onFriend(RelationFriendEvent event) {
        handleFriend(event);
    }

    @Async
    @EventListener
    public void onBlock(RelationBlockEvent event) {
        handleBlock(event);
    }

    @RabbitListener(queues = "relation.follow.queue")
    public void consumeFollow(RelationFollowEvent event) {
        try {
            handleFollow(event);
        } catch (Exception e) {
            log.error("MQ follow消费失败，发送至死信 source={} target={}", event.sourceId(), event.targetId(), e);
            throw new AmqpRejectAndDontRequeueException("follow failed", e);
        }
    }

    @RabbitListener(queues = "relation.friend.queue")
    public void consumeFriend(RelationFriendEvent event) {
        try {
            handleFriend(event);
        } catch (Exception e) {
            log.error("MQ friend消费失败，发送至死信 {} <-> {}", event.sourceId(), event.targetId(), e);
            throw new AmqpRejectAndDontRequeueException("friend failed", e);
        }
    }

    @RabbitListener(queues = "relation.block.queue")
    public void consumeBlock(RelationBlockEvent event) {
        try {
            handleBlock(event);
        } catch (Exception e) {
            log.error("MQ block消费失败，发送至死信 source={} target={}", event.sourceId(), event.targetId(), e);
            throw new AmqpRejectAndDontRequeueException("block failed", e);
        }
    }

    private void handleFollow(RelationFollowEvent event) {
        log.info("Handle follow event source={} target={} status={}", event.sourceId(), event.targetId(), event.status());
        String fp = "follow:" + event.sourceId() + ":" + event.targetId() + ":" + event.status();
        if (!relationEventInboxPort.save("FOLLOW", fp, event.toString())) {
            log.debug("Skip duplicate follow event {}", fp);
            return;
        }
        feedService.timeline(event.targetId(), null, 1, "FOLLOW_EVENT");
        NotificationListVO list = interactionService.notifications(event.targetId(), null);
        riskService.userStatus(event.sourceId());
        relationEventInboxPort.markDone(fp);
        log.debug("Follow fanout finished, notifications size={}", list.getNotifications() == null ? 0 : list.getNotifications().size());
    }

    private void handleFriend(RelationFriendEvent event) {
        log.info("Handle friend event {} <-> {}", event.sourceId(), event.targetId());
        String fp = "friend:" + event.sourceId() + ":" + event.targetId();
        if (!relationEventInboxPort.save("FRIEND", fp, event.toString())) {
            log.debug("Skip duplicate friend event {}", fp);
            return;
        }
        feedService.profile(event.sourceId(), event.targetId(), null, 1);
        feedService.profile(event.targetId(), event.sourceId(), null, 1);
        interactionService.notifications(event.sourceId(), null);
        interactionService.notifications(event.targetId(), null);
        relationEventInboxPort.markDone(fp);
    }

    private void handleBlock(RelationBlockEvent event) {
        log.info("Handle block event source={} target={}", event.sourceId(), event.targetId());
        String fp = "block:" + event.sourceId() + ":" + event.targetId();
        if (!relationEventInboxPort.save("BLOCK", fp, event.toString())) {
            log.debug("Skip duplicate block event {}", fp);
            return;
        }
        OperationResultVO result = riskService.userStatus(event.targetId()) != null
                ? OperationResultVO.builder().success(true).status("BLOCK_REFRESHED").build()
                : OperationResultVO.builder().success(false).status("BLOCK_REFRESH_FAILED").build();
        relationEventInboxPort.markDone(fp);
        log.debug("Block fanout status={}", result.getStatus());
    }
}
