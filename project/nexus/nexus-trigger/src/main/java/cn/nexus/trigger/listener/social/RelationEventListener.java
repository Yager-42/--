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
 * 关系事件监听器：消费 MQ 后把关注、取关、拉黑副作用推进到 Feed、通知和风控读侧。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
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

    /**
     * 消费关注事件。
     *
     * @param event 关注事件消息，类型：{@link RelationFollowEvent}
     * @return 无返回值，类型：{@code void}
     */
    @RabbitListener(queues = "relation.follow.queue")
    public void consumeFollow(RelationFollowEvent event) {
        try {
            handleFollow(event);
        } catch (Exception e) {
            log.error("MQ follow消费失败，发送至死信 eventId={} source={} target={}", event.eventId(), event.sourceId(), event.targetId(), e);
            throw new AmqpRejectAndDontRequeueException("follow failed", e);
        }
    }

    /**
     * 消费拉黑事件。
     *
     * @param event 拉黑事件消息，类型：{@link RelationBlockEvent}
     * @return 无返回值，类型：{@code void}
     */
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
        // 先做 inbox 去重，再推进副作用，这样 MQ 至少一次投递也不会把补偿逻辑重复执行。
        if ("ACTIVE".equalsIgnoreCase(event.status())) {
            feedFollowCompensationService.onFollow(event.sourceId(), event.targetId());
            feedAuthorCategoryStateMachine.onFollowerCountChanged(event.targetId());
        } else if ("UNFOLLOW".equalsIgnoreCase(event.status())) {
            feedFollowCompensationService.onUnfollow(event.sourceId(), event.targetId());
            feedAuthorCategoryStateMachine.onFollowerCountChanged(event.targetId());
        }
        // 通知与用户状态这里属于“读一次触发旁路”的轻量副作用，不反向改变关系真相。
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
        // block 会影响双方的“是否还是大 V 候选”，所以这里刷新两侧作者分类状态。
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
