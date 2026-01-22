package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedCoreFansRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.trigger.mq.config.FeedCoreFansMqConfig;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Feed 铁粉集合生成消费者：复用 {@code interaction.notify} 事件旁路，近似“高频互动粉丝”。
 *
 * <p>规则（最小可落地）：</p>
 * <ul>
 *   <li>只处理 {@link EventType#LIKE_ADDED}（仅 POST）与 {@link EventType#COMMENT_CREATED}（POST/COMMENT 都可）。</li>
 *   <li>只为“当前仍关注作者”的用户生成铁粉：避免把路人写进 {@code feed:corefans:{authorId}}。</li>
 *   <li>写入幂等：Redis SET 的 {@code SADD} 天然幂等，多次消费不会产生重复。</li>
 * </ul>
 *
 * @author codex
 * @since 2026-01-22
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedCoreFansConsumer {

    private static final int RELATION_FOLLOW = 1;
    private static final int RELATION_STATUS_ACTIVE = 1;

    private final IFeedCoreFansRepository feedCoreFansRepository;
    private final IRelationRepository relationRepository;
    private final IContentRepository contentRepository;
    private final ICommentRepository commentRepository;

    /**
     * 消费互动通知事件并生成铁粉集合。
     *
     * @param event 互动通知事件 {@link InteractionNotifyEvent}
     */
    @RabbitListener(queues = FeedCoreFansMqConfig.Q_FEED_CORE_FANS)
    public void onMessage(InteractionNotifyEvent event) {
        try {
            if (event == null) {
                return;
            }
            EventType eventType = event.getEventType();
            if (eventType != EventType.LIKE_ADDED && eventType != EventType.COMMENT_CREATED) {
                return;
            }
            Long fromUserId = event.getFromUserId();
            if (fromUserId == null) {
                return;
            }

            String targetType = normalizeType(event.getTargetType());
            if (targetType == null) {
                return;
            }
            // 点赞只认“点赞帖子”：点赞评论不等价于对作者的强互动（避免误判）。
            if (eventType == EventType.LIKE_ADDED && !"POST".equals(targetType)) {
                return;
            }

            Long postId = resolvePostId(event, targetType);
            if (postId == null) {
                return;
            }

            ContentPostEntity post = contentRepository.findPost(postId);
            Long authorId = post == null ? null : post.getUserId();
            if (authorId == null) {
                return;
            }
            if (authorId.equals(fromUserId)) {
                return;
            }

            RelationEntity follow = relationRepository.findRelation(fromUserId, authorId, RELATION_FOLLOW);
            if (follow == null || follow.getStatus() == null || follow.getStatus() != RELATION_STATUS_ACTIVE) {
                return;
            }

            feedCoreFansRepository.addCoreFan(authorId, fromUserId);
        } catch (Exception e) {
            log.error("MQ feed corefans consume failed, eventId={}, eventType={}",
                    event == null ? null : event.getEventId(),
                    event == null ? null : event.getEventType(),
                    e);
            throw new AmqpRejectAndDontRequeueException("feed corefans consume failed", e);
        }
    }

    private Long resolvePostId(InteractionNotifyEvent event, String targetType) {
        if (event == null) {
            return null;
        }
        Long postId = event.getPostId();
        if ("POST".equals(targetType)) {
            return postId == null ? event.getTargetId() : postId;
        }
        if ("COMMENT".equals(targetType)) {
            if (postId != null) {
                return postId;
            }
            Long commentId = event.getTargetId();
            if (commentId == null) {
                return null;
            }
            CommentBriefVO brief = commentRepository.getBrief(commentId);
            return brief == null ? null : brief.getPostId();
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

