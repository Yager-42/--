package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.*;

/**
 * 互动服务。
 */
public interface IInteractionService {

    ReactionResultVO react(Long userId, Long targetId, String targetType, String type, String action, String requestId);

    ReactionStateVO reactionState(Long userId, Long targetId, String targetType, String type);

    CommentResultVO comment(Long userId, Long postId, Long parentId, String content);

    OperationResultVO pinComment(Long userId, Long commentId, Long postId);

    OperationResultVO deleteComment(Long userId, Long commentId);

    NotificationListVO notifications(Long userId, String cursor);

    /**
     * 标记单条通知已读（幂等）。
     */
    OperationResultVO readNotification(Long userId, Long notificationId);

    /**
     * 全部通知标记已读（幂等）。
     */
    OperationResultVO readAllNotifications(Long userId);

    TipResultVO tip(Long toUserId, java.math.BigDecimal amount, String currency, Long postId);

    PollCreateResultVO createPoll(String question, java.util.List<String> options, Boolean allowMulti, Integer expireSeconds);

    PollVoteResultVO vote(Long pollId, java.util.List<Long> optionIds);

    WalletBalanceVO balance(String currencyType);
}
