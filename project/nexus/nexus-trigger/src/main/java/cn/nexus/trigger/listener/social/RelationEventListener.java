package cn.nexus.trigger.listener.social;

import cn.nexus.domain.social.adapter.port.IRelationEventInboxPort;
import cn.nexus.domain.social.model.valobj.NotificationListVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.service.FeedAuthorCategoryStateMachine;
import cn.nexus.domain.social.service.IFeedFollowCompensationService;
import cn.nexus.domain.social.service.IInteractionService;
import cn.nexus.domain.social.service.IRiskService;
import cn.nexus.infrastructure.adapter.social.port.RelationBlockEvent;
import cn.nexus.infrastructure.adapter.social.port.RelationFollowEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 关系事件监听，消费 MQ 并触达 Feed/通知/风控。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RelationEventListener {

    private final IFeedFollowCompensationService feedFollowCompensationService;
    private final IInteractionService interactionService;
    private final IRiskService riskService;
    private final IRelationEventInboxPort relationEventInboxPort;
    private final FeedAuthorCategoryStateMachine feedAuthorCategoryStateMachine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @RabbitListener(queues = "relation.follow.queue")
    public void consumeFollow(RelationFollowEvent event) {
        try {
            handleFollow(event);
        } catch (Exception e) {
            log.error("MQ follow消费失败，发送至死信 eventId={} source={} target={}", event.eventId(), event.sourceId(), event.targetId(), e);
            throw new AmqpRejectAndDontRequeueException("follow failed", e);
        }
    }

    @RabbitListener(queues = "relation.block.queue")
    public void consumeBlock(RelationBlockEvent event) {
        try {
            handleBlock(event);
        } catch (Exception e) {
            log.error("MQ block消费失败，发送至死信 eventId={} source={} target={}", event.eventId(), event.sourceId(), event.targetId(), e);
            throw new AmqpRejectAndDontRequeueException("block failed", e);
        }
    }

    private void handleFollow(RelationFollowEvent event) {
        String fingerprint = String.valueOf(event.eventId());
        log.info("Handle follow event eventId={} source={} target={} status={}", event.eventId(), event.sourceId(), event.targetId(), event.status());
        if (!relationEventInboxPort.save("FOLLOW", fingerprint, toPayload(event))) {
            log.debug("Skip duplicate follow event {}", fingerprint);
            return;
        }
        if ("ACTIVE".equalsIgnoreCase(event.status())) {
            feedFollowCompensationService.onFollow(event.sourceId(), event.targetId());
            feedAuthorCategoryStateMachine.onFollowerCountChanged(event.targetId());
        } else if ("UNFOLLOW".equalsIgnoreCase(event.status())) {
            feedFollowCompensationService.onUnfollow(event.sourceId(), event.targetId());
            feedAuthorCategoryStateMachine.onFollowerCountChanged(event.targetId());
        }
        NotificationListVO list = interactionService.notifications(event.targetId(), null);
        riskService.userStatus(event.sourceId());
        relationEventInboxPort.markDone(fingerprint);
        log.debug("Follow fanout finished, notifications size={}", list.getNotifications() == null ? 0 : list.getNotifications().size());
    }

    private void handleBlock(RelationBlockEvent event) {
        String fingerprint = String.valueOf(event.eventId());
        log.info("Handle block event eventId={} source={} target={}", event.eventId(), event.sourceId(), event.targetId());
        if (!relationEventInboxPort.save("BLOCK", fingerprint, toPayload(event))) {
            log.debug("Skip duplicate block event {}", fingerprint);
            return;
        }
        OperationResultVO result = riskService.userStatus(event.targetId()) != null
                ? OperationResultVO.builder().success(true).status("BLOCK_REFRESHED").build()
                : OperationResultVO.builder().success(false).status("BLOCK_REFRESH_FAILED").build();
        feedAuthorCategoryStateMachine.onFollowerCountChanged(event.sourceId());
        feedAuthorCategoryStateMachine.onFollowerCountChanged(event.targetId());
        relationEventInboxPort.markDone(fingerprint);
        log.debug("Block fanout status={}", result.getStatus());
    }

    private String toPayload(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return String.valueOf(event);
        }
    }
}
