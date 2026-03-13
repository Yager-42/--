package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IInteractionCommentInboxPort;
import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
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
        commentRepository.addReplyCount(event.getRootCommentId(), event.getDelta());

        CommentBriefVO root = commentRepository.getBrief(event.getRootCommentId());
        if (root == null) {
            return;
        }
        registerHotRankAfterCommit(event.getPostId(), event.getRootCommentId(), root);
    }

    private void registerHotRankAfterCommit(Long postId, Long rootCommentId, CommentBriefVO root) {
        if (postId == null || rootCommentId == null || root == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refreshHotRankBestEffort(postId, rootCommentId, root);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refreshHotRankBestEffort(postId, rootCommentId, root);
            }
        });
    }

    private void refreshHotRankBestEffort(Long postId, Long rootCommentId, CommentBriefVO root) {
        try {
            if (root.getStatus() == null || root.getStatus() != 1) {
                hotRankRepository.remove(postId, rootCommentId);
                return;
            }
            double score = safe(root.getLikeCount()) * 10D + safe(root.getReplyCount()) * 20D;
            hotRankRepository.upsert(postId, rootCommentId, score);
        } catch (Exception e) {
            log.warn("comment hot rank refresh failed, postId={}, rootCommentId={}", postId, rootCommentId, e);
        }
    }

    private long safe(Long v) {
        return v == null ? 0L : v;
    }
}
