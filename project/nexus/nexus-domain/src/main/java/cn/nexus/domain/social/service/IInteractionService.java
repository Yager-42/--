package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.*;

/**
 * 互动服务。
 */
public interface IInteractionService {

    ReactionResultVO react(Long targetId, String targetType, String type, String action);

    CommentResultVO comment(Long postId, Long parentId, String content, java.util.List<Long> mentions);

    OperationResultVO pinComment(Long commentId, Long postId);

    NotificationListVO notifications(Long userId, String cursor);

    TipResultVO tip(Long toUserId, java.math.BigDecimal amount, String currency, Long postId);

    PollCreateResultVO createPoll(String question, java.util.List<String> options, Boolean allowMulti, Integer expireSeconds);

    PollVoteResultVO vote(Long pollId, java.util.List<Long> optionIds);

    WalletBalanceVO balance(String currencyType);
}
