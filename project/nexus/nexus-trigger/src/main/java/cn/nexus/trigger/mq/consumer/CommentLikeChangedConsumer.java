package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.IInteractionCommentInboxPort;
import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 点赞数变更事件消费者：只刷新热榜分数；计数正确性以 object counter 为准。
 *
 * @author rr
 * @author codex
 * @since 2026-01-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentLikeChangedConsumer {

    private static final String EVENT_TYPE = "COMMENT_LIKE_CHANGED";

    private final IInteractionCommentInboxPort inboxPort;
    private final ICommentRepository commentRepository;
    private final ICommentHotRankRepository hotRankRepository;
    private final IObjectCounterService objectCounterService;

    /**
     * 消费单条消息。
     *
     * @param event 事件对象。类型：{@link CommentLikeChangedEvent}
     */
    @RabbitListener(queues = InteractionCommentMqConfig.Q_COMMENT_LIKE_CHANGED)
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(CommentLikeChangedEvent event) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()
                || event.getRootCommentId() == null || event.getPostId() == null || event.getDelta() == null) {
            return;
        }
        if (!inboxPort.save(event.getEventId(), EVENT_TYPE, null)) {
            return;
        }

        CommentBriefVO root = commentRepository.getBrief(event.getRootCommentId());
        if (root == null) {
            return;
        }
        long likeCount = readCommentLikeCount(event.getRootCommentId(), root);
        registerHotRankAfterCommit(event.getPostId(), event.getRootCommentId(), root, likeCount);
    }

    private void registerHotRankAfterCommit(Long postId, Long rootCommentId, CommentBriefVO root, long likeCount) {
        if (postId == null || rootCommentId == null || root == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refreshHotRankBestEffort(postId, rootCommentId, root, likeCount);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 执行 afterCommit 逻辑。
             *
             */
            @Override
            public void afterCommit() {
                refreshHotRankBestEffort(postId, rootCommentId, root, likeCount);
            }
        });
    }

    private void refreshHotRankBestEffort(Long postId, Long rootCommentId, CommentBriefVO root, long likeCount) {
        try {
            if (root.getStatus() == null || root.getStatus() != 1) {
                hotRankRepository.remove(postId, rootCommentId);
                return;
            }
            double score = safe(likeCount) * 10D;
            hotRankRepository.upsert(postId, rootCommentId, score);
        } catch (Exception e) {
            // 热榜是派生缓存：失败不影响主流程，避免用它拖死 MQ 消费。
            log.warn("comment hot rank refresh failed, postId={}, rootCommentId={}", postId, rootCommentId, e);
        }
    }

    private long readCommentLikeCount(Long commentId, CommentBriefVO root) {
        try {
            Map<String, Long> counts = objectCounterService.getCounts(
                    cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO.COMMENT,
                    commentId,
                    List.of(ObjectCounterType.LIKE));
            Long like = counts == null ? null : counts.get(ObjectCounterType.LIKE.getCode());
            return like == null ? safe(root.getLikeCount()) : safe(like);
        } catch (Exception e) {
            return safe(root.getLikeCount());
        }
    }

    private long safe(Long v) {
        return v == null ? 0L : Math.max(0L, v);
    }
}
