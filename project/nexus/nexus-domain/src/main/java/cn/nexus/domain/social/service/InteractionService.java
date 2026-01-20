package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 互动服务实现。
 */
@Service
@RequiredArgsConstructor
public class InteractionService implements IInteractionService {

    private final ISocialIdPort socialIdPort;
    private final IReactionLikeService reactionLikeService;

    @Override
    public ReactionResultVO react(Long userId, Long targetId, String targetType, String type, String action, String requestId) {
        ReactionTargetVO target = parseTarget(targetId, targetType, type);
        ReactionActionEnumVO actionEnum = ReactionActionEnumVO.from(action);
        if (actionEnum == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        return reactionLikeService.applyReaction(userId, target, actionEnum, requestId);
    }

    @Override
    public ReactionStateVO reactionState(Long userId, Long targetId, String targetType, String type) {
        ReactionTargetVO target = parseTarget(targetId, targetType, type);
        return reactionLikeService.queryState(userId, target);
    }

    @Override
    public CommentResultVO comment(Long userId, Long postId, Long parentId, String content, List<Long> mentions) {
        return CommentResultVO.builder()
                .commentId(socialIdPort.nextId())
                .createTime(socialIdPort.now())
                .build();
    }

    @Override
    public OperationResultVO pinComment(Long commentId, Long postId) {
        return OperationResultVO.builder()
                .success(true)
                .id(commentId)
                .status("PINNED")
                .message("已置顶")
                .build();
    }

    @Override
    public NotificationListVO notifications(Long userId, String cursor) {
        NotificationVO notification = NotificationVO.builder()
                .title("占位通知")
                .content("您有新的互动")
                .createTime(socialIdPort.now())
                .build();
        return NotificationListVO.builder()
                .notifications(List.of(notification))
                .nextCursor("next-" + socialIdPort.nextId())
                .build();
    }

    @Override
    public TipResultVO tip(Long toUserId, BigDecimal amount, String currency, Long postId) {
        return TipResultVO.builder()
                .txId("tx-" + socialIdPort.nextId())
                .effectUrl("https://effect/mock")
                .build();
    }

    @Override
    public PollCreateResultVO createPoll(String question, List<String> options, Boolean allowMulti, Integer expireSeconds) {
        return PollCreateResultVO.builder().pollId(socialIdPort.nextId()).build();
    }

    @Override
    public PollVoteResultVO vote(Long pollId, List<Long> optionIds) {
        return PollVoteResultVO.builder().updatedStats("VOTED").build();
    }

    @Override
    public WalletBalanceVO balance(String currencyType) {
        return WalletBalanceVO.builder()
                .currencyType(currencyType)
                .amount("100.00")
                .frozenAmount("0.00")
                .build();
    }

    private ReactionTargetVO parseTarget(Long targetId, String targetType, String type) {
        ReactionTargetTypeEnumVO targetTypeEnum = ReactionTargetTypeEnumVO.from(targetType);
        ReactionTypeEnumVO reactionTypeEnum = ReactionTypeEnumVO.from(type);
        if (targetId == null || targetTypeEnum == null || reactionTypeEnum == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        return ReactionTargetVO.builder()
                .targetType(targetTypeEnum)
                .targetId(targetId)
                .reactionType(reactionTypeEnum)
                .build();
    }
}
