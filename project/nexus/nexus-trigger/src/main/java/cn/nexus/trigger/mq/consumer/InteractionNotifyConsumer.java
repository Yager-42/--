package cn.nexus.trigger.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.nexus.domain.social.adapter.port.IInteractionNotifyInboxPort;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IInteractionNotificationRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.InteractionNotificationUpsertCmd;
import cn.nexus.trigger.mq.config.InteractionNotifyMqConfig;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 通知统一事件消费者：幂等去重 + 目标归属解析 + 聚合 UPSERT。
 *
 * @author codex
 * @since 2026-01-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionNotifyConsumer {

    private final IInteractionNotifyInboxPort inboxPort;
    private final IInteractionNotificationRepository notificationRepository;
    private final IContentRepository contentRepository;
    private final ICommentRepository commentRepository;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = InteractionNotifyMqConfig.Q_INTERACTION_NOTIFY)
    public void onMessage(InteractionNotifyEvent event) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()) {
            return;
        }
        String eventId = event.getEventId();
        String eventType = event.getEventType() == null ? "" : event.getEventType().name();

        // payload 只用于排障与重放分析：必须是可复用的 JSON（见通知业务文档 6.4）。
        String payload = toJson(event);
        if (!inboxPort.save(eventId, eventType, payload)) {
            return;
        }

        try {
            String targetType = validateOrThrow(event);
            process(event, targetType);
            inboxPort.markDone(eventId);
        } catch (Exception e) {
            inboxPort.markFail(eventId);
            log.error("notify consume failed, eventId={}, eventType={}", eventId, eventType, e);
            throw new AmqpRejectAndDontRequeueException("notify failed", e);
        }
    }

    private String validateOrThrow(InteractionNotifyEvent event) {
        // 通知旁路必须“坏数据就爆炸”：否则你只会得到一堆“悄悄丢了”的假成功。
        EventType et = event.getEventType();
        if (et == null) {
            throw new IllegalArgumentException("eventType is null");
        }
        String targetType = normalizeType(event.getTargetType());
        if (targetType == null) {
            throw new IllegalArgumentException("targetType is invalid");
        }
        if (event.getTargetId() == null) {
            throw new IllegalArgumentException("targetId is null");
        }
        if (event.getFromUserId() == null) {
            throw new IllegalArgumentException("fromUserId is null");
        }
        if (et == EventType.COMMENT_MENTIONED && event.getToUserId() == null) {
            throw new IllegalArgumentException("toUserId is required for COMMENT_MENTIONED");
        }
        return targetType;
    }

    private void process(InteractionNotifyEvent event, String targetType) {
        EventType et = event.getEventType();
        Long targetId = event.getTargetId();
        Long fromUserId = event.getFromUserId();

        Long targetOwnerUserId = null;
        CommentBriefVO brief = null;
        if ("POST".equals(targetType)) {
            ContentPostEntity post = contentRepository.findPost(targetId);
            targetOwnerUserId = post == null ? null : post.getUserId();
        } else if ("COMMENT".equals(targetType)) {
            brief = commentRepository.getBrief(targetId);
            targetOwnerUserId = brief == null ? null : brief.getUserId();
        }

        Long toUserId = event.getToUserId() == null ? targetOwnerUserId : event.getToUserId();
        if (toUserId == null) {
            return;
        }

        // 过滤自互动：不允许给自己发通知。
        if (toUserId.equals(fromUserId)) {
            return;
        }

        // 提及去重：如果被提及者就是主收件人，则丢弃（避免双通知）。
        if (et == EventType.COMMENT_MENTIONED && targetOwnerUserId != null && toUserId.equals(targetOwnerUserId)) {
            return;
        }

        String bizType = deriveBizType(et, targetType);
        if (bizType == null) {
            return;
        }

        Long postId = event.getPostId();
        Long rootCommentId = event.getRootCommentId();
        if ("POST".equals(targetType)) {
            postId = postId == null ? targetId : postId;
        } else if ("COMMENT".equals(targetType)) {
            if (brief != null) {
                if (postId == null) {
                    postId = brief.getPostId();
                }
                if (rootCommentId == null) {
                    rootCommentId = brief.getRootId() == null ? brief.getCommentId() : brief.getRootId();
                }
            }
        }

        InteractionNotificationUpsertCmd cmd = InteractionNotificationUpsertCmd.builder()
                .toUserId(toUserId)
                .bizType(bizType)
                .targetType(targetType)
                .targetId(targetId)
                .postId(postId)
                .rootCommentId(rootCommentId)
                .lastActorUserId(fromUserId)
                .lastCommentId(event.getCommentId())
                .delta(1L)
                .build();
        notificationRepository.upsertIncrement(cmd);
    }

    private String toJson(InteractionNotifyEvent event) {
        if (event == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception ignored) {
            // JSON 序列化失败也不能影响幂等入库：payload 只是辅助信息，但至少要保证它是 JSON 且不为空。
            return "{\"eventId\":\"" + escapeJson(event.getEventId()) + "\",\"eventType\":\""
                    + escapeJson(event.getEventType() == null ? "" : event.getEventType().name())
                    + "\",\"_error\":\"SERIALIZE_FAILED\"}";
        }
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String deriveBizType(EventType eventType, String targetType) {
        if (eventType == null || targetType == null) {
            return null;
        }
        if (eventType == EventType.LIKE_ADDED) {
            return "POST".equals(targetType) ? "POST_LIKED" : ("COMMENT".equals(targetType) ? "COMMENT_LIKED" : null);
        }
        if (eventType == EventType.COMMENT_CREATED) {
            return "POST".equals(targetType) ? "POST_COMMENTED" : ("COMMENT".equals(targetType) ? "COMMENT_REPLIED" : null);
        }
        if (eventType == EventType.COMMENT_MENTIONED) {
            return "COMMENT_MENTIONED";
        }
        return null;
    }

    private String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        if ("POST".equals(v) || "COMMENT".equals(v)) {
            return v;
        }
        return null;
    }
}
