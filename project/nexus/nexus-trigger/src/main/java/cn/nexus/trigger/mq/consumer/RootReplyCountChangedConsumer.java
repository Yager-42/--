package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.counter.adapter.port.IObjectCounterPort;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.IInteractionCommentInboxPort;
import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.RootReplyCountChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 回复数变更事件消费者：更新一级评论 reply_count + 刷新热榜分数。
 *
 * @author rr
 * @author codex
 * @since 2026-01-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RootReplyCountChangedConsumer {

    private static final String EVENT_TYPE = "ROOT_REPLY_COUNT_CHANGED";

    private final IInteractionCommentInboxPort inboxPort;
    private final ICommentRepository commentRepository;
    private final ICommentHotRankRepository hotRankRepository;
    private final IObjectCounterPort objectCounterPort;

    /**
     * 消费单条消息。
     *
     * @param event 事件对象。类型：{@link RootReplyCountChangedEvent}
     */
    @RabbitListener(queues = InteractionCommentMqConfig.Q_REPLY_COUNT_CHANGED)
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(RootReplyCountChangedEvent event) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()
                || event.getRootCommentId() == null || event.getPostId() == null || event.getDelta() == null) {
            return;
        }
        if (!inboxPort.save(event.getEventId(), EVENT_TYPE, null)) {
            return;
        }
        long replyCount = objectCounterPort.increment(target(event.getRootCommentId(), ObjectCounterType.REPLY), event.getDelta());
        commentRepository.addReplyCount(event.getRootCommentId(), event.getDelta());

        CommentBriefVO root = commentRepository.getBrief(event.getRootCommentId());
        if (root == null) {
            return;
        }
        long likeCount = objectCounterPort.getCount(target(event.getRootCommentId(), ObjectCounterType.LIKE));
        registerHotRankAfterCommit(event.getPostId(), event.getRootCommentId(), root, likeCount, replyCount);
    }

    private void registerHotRankAfterCommit(Long postId, Long rootCommentId, CommentBriefVO root, long likeCount, long replyCount) {
        if (postId == null || rootCommentId == null || root == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refreshHotRankBestEffort(postId, rootCommentId, root, likeCount, replyCount);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 执行 afterCommit 逻辑。
             *
             */
            @Override
            public void afterCommit() {
                refreshHotRankBestEffort(postId, rootCommentId, root, likeCount, replyCount);
            }
        });
    }

    private void refreshHotRankBestEffort(Long postId, Long rootCommentId, CommentBriefVO root, long likeCount, long replyCount) {
        try {
            if (root.getStatus() == null || root.getStatus() != 1) {
                hotRankRepository.remove(postId, rootCommentId);
                return;
            }
            double score = safe(likeCount) * 10D + safe(replyCount) * 20D;
            hotRankRepository.upsert(postId, rootCommentId, score);
        } catch (Exception e) {
            log.warn("comment hot rank refresh failed, postId={}, rootCommentId={}", postId, rootCommentId, e);
        }
    }

    private ObjectCounterTarget target(Long commentId, ObjectCounterType counterType) {
        return ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.COMMENT)
                .targetId(commentId)
                .counterType(counterType)
                .build();
    }

    private long safe(long v) {
        return Math.max(0L, v);
    }
}
