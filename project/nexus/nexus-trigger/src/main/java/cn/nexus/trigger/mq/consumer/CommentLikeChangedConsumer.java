package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 点赞数变更事件消费者：更新一级评论 like_count + 刷新热榜分数（可选）。
 *
 * @author codex
 * @since 2026-01-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentLikeChangedConsumer {

    private final ICommentRepository commentRepository;
    private final ICommentHotRankRepository hotRankRepository;

    @RabbitListener(queues = InteractionCommentMqConfig.Q_COMMENT_LIKE_CHANGED)
    public void onMessage(CommentLikeChangedEvent event) {
        if (event == null || event.getRootCommentId() == null || event.getPostId() == null) {
            return;
        }
        commentRepository.addLikeCount(event.getRootCommentId(), event.getDelta());

        CommentBriefVO root = commentRepository.getBrief(event.getRootCommentId());
        if (root == null) {
            return;
        }
        if (root.getStatus() == null || root.getStatus() != 1) {
            hotRankRepository.remove(event.getPostId(), event.getRootCommentId());
            return;
        }
        double score = safe(root.getLikeCount()) * 10D + safe(root.getReplyCount()) * 20D;
        hotRankRepository.upsert(event.getPostId(), event.getRootCommentId(), score);
    }

    private long safe(Long v) {
        return v == null ? 0L : v;
    }
}

