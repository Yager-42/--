package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 互动服务实现。
 */
@Service
@RequiredArgsConstructor
public class InteractionService implements IInteractionService {

    private final ISocialIdPort socialIdPort;
    private final IReactionRepository reactionRepository;

    /**
     * like:win / like:touch TTL（秒）。必须 >= delaySeconds * 2，避免 flush 执行期间 key 过期导致窗口丢失。
     */
    @Value("${interaction.like.syncTtlSeconds:180}")
    private long syncTtlSeconds;

    @Override
    public ReactionResultVO react(Long userId, Long targetId, String targetType, String type, String action) {
        if (userId == null || targetId == null || targetId <= 0) {
            return ReactionResultVO.builder().currentCount(0L).success(false).delta(0L).needSchedule(false).build();
        }

        // 当前版本只落地 LIKE（点赞）。其它态势（LOVE/ANGRY 等）后续再扩展到独立计数与状态。
        String normalizedType = normalizeUpper(type);
        if (normalizedType != null && !normalizedType.isBlank() && !"LIKE".equals(normalizedType)) {
            return ReactionResultVO.builder().currentCount(0L).success(false).delta(0L).needSchedule(false).build();
        }

        String normalizedTargetType = normalizeUpper(targetType);
        if (!isSupportedTargetType(normalizedTargetType)) {
            return ReactionResultVO.builder().currentCount(0L).success(false).delta(0L).needSchedule(false).build();
        }

        String normalizedAction = normalizeUpper(action);
        if (!isSupportedAction(normalizedAction)) {
            return ReactionResultVO.builder().currentCount(0L).success(false).delta(0L).needSchedule(false).build();
        }

        long ttlSeconds = Math.max(1L, syncTtlSeconds);
        ReactionToggleResultVO res = reactionRepository.toggle(userId, normalizedTargetType, targetId, normalizedAction, ttlSeconds);
        if (res == null) {
            return ReactionResultVO.builder().currentCount(0L).success(false).delta(0L).needSchedule(false).build();
        }
        return ReactionResultVO.builder()
                .currentCount(res.getCurrentCount() == null ? 0L : res.getCurrentCount())
                .success(true)
                .delta(res.getDelta() == null ? 0L : res.getDelta())
                .needSchedule(res.isNeedSchedule())
                .build();
    }

    @Override
    public ReactionStateVO reactionState(Long userId, Long targetId, String targetType) {
        if (userId == null || targetId == null || targetId <= 0) {
            return ReactionStateVO.builder().likeCount(0L).likedByMe(false).build();
        }
        String normalizedTargetType = normalizeUpper(targetType);
        if (!isSupportedTargetType(normalizedTargetType)) {
            return ReactionStateVO.builder().likeCount(0L).likedByMe(false).build();
        }
        return reactionRepository.getState(userId, normalizedTargetType, targetId);
    }

    @Override
    public ReactionBatchStateVO batchState(Long userId, List<ReactionTargetVO> targets) {
        if (userId == null || targets == null || targets.isEmpty()) {
            return ReactionBatchStateVO.builder().items(Collections.emptyList()).build();
        }
        List<ReactionTargetVO> normalized = targets.stream()
                .map(t -> ReactionTargetVO.builder()
                        .targetId(t == null ? null : t.getTargetId())
                        .targetType(normalizeUpper(t == null ? null : t.getTargetType()))
                        .build())
                .toList();
        return reactionRepository.getBatchState(userId, normalized);
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

    private static boolean isSupportedTargetType(String targetType) {
        return "POST".equals(targetType) || "COMMENT".equals(targetType);
    }

    private static boolean isSupportedAction(String action) {
        return "ADD".equals(action) || "REMOVE".equals(action);
    }

    private static String normalizeUpper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }
}
