package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.*;

/**
 * 互动服务。
 */
public interface IInteractionService {

    ReactionResultVO react(Long userId, Long targetId, String targetType, String type, String action, String requestId);

    ReactionStateVO reactionState(Long userId, Long targetId, String targetType, String type);

    CommentResultVO comment(Long userId, Long postId, Long parentId, String content, Long commentId);

    OperationResultVO applyCommentRiskReviewResult(Long commentId, String finalResult, String reasonCode);

    OperationResultVO pinComment(Long userId, Long commentId, Long postId);

    OperationResultVO deleteComment(Long userId, Long commentId);

    NotificationListVO notifications(Long userId, String cursor);

    OperationResultVO readNotification(Long userId, Long notificationId);

    OperationResultVO readAllNotifications(Long userId);

}
