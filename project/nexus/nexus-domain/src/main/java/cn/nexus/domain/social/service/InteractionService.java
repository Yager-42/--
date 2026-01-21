package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ICommentEventPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentPinRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 互动服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionService implements IInteractionService {

    private final ISocialIdPort socialIdPort;
    private final IReactionLikeService reactionLikeService;
    private final ICommentRepository commentRepository;
    private final ICommentPinRepository commentPinRepository;
    private final ICommentHotRankRepository commentHotRankRepository;
    private final IContentRepository contentRepository;
    private final ICommentEventPort commentEventPort;

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
    @Transactional(rollbackFor = Exception.class)
    public CommentResultVO comment(Long userId, Long postId, Long parentId, String content, List<Long> mentions) {
        requireNonNull(userId, "userId");
        requireNonNull(postId, "postId");

        Long nowMs = socialIdPort.now();
        Long commentId = socialIdPort.nextId();

        Long rootId = null;
        Long parentIdToSave = null;
        Long replyToId = null;

        if (parentId != null) {
            CommentBriefVO parent = commentRepository.getBrief(parentId);
            if (parent == null
                    || parent.getPostId() == null
                    || !postId.equals(parent.getPostId())
                    || parent.getStatus() == null
                    || parent.getStatus() != 1) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
            }
            rootId = parent.getRootId() == null ? parent.getCommentId() : parent.getRootId();
            parentIdToSave = parent.getCommentId();
            replyToId = parent.getCommentId();
        }

        commentRepository.insert(commentId, postId, userId, rootId, parentIdToSave, replyToId, content, nowMs);

        publishCreated(commentId, postId, rootId, userId, nowMs);
        if (rootId != null) {
            publishReplyCountChanged(rootId, postId, +1L, nowMs);
        }

        // mentions 当前仅透传占位；真正通知在通知子系统完成（见 notification-business-implementation.md）
        return CommentResultVO.builder().commentId(commentId).createTime(nowMs).build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO pinComment(Long userId, Long commentId, Long postId) {
        requireNonNull(userId, "userId");
        requireNonNull(commentId, "commentId");
        requireNonNull(postId, "postId");

        Long nowMs = socialIdPort.now();

        ContentPostEntity post = contentRepository.findPost(postId);
        if (post == null) {
            return fail(commentId, "NOT_FOUND", "帖子不存在");
        }
        if (post.getUserId() == null || !post.getUserId().equals(userId)) {
            return fail(commentId, "NO_PERMISSION", "无权限");
        }

        CommentBriefVO c = commentRepository.getBrief(commentId);
        if (c == null) {
            return fail(commentId, "NOT_FOUND", "评论不存在");
        }
        if (c.getPostId() == null
                || !postId.equals(c.getPostId())
                || c.getRootId() != null
                || c.getStatus() == null
                || c.getStatus() != 1) {
            return fail(commentId, "ILLEGAL_PARAMETER", "非法参数");
        }

        commentPinRepository.pin(postId, commentId, nowMs);
        return ok(commentId, "PINNED", "已置顶");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO deleteComment(Long userId, Long commentId) {
        requireNonNull(userId, "userId");
        requireNonNull(commentId, "commentId");

        Long nowMs = socialIdPort.now();
        CommentBriefVO c = commentRepository.getBrief(commentId);
        if (c == null) {
            return ok(commentId, "DELETED", "已删除");
        }
        if (!hasDeletePermission(userId, c)) {
            return fail(commentId, "NO_PERMISSION", "无权限");
        }

        // 回复：只有 status=1->2 成功才允许扣 reply_count
        if (c.getRootId() != null) {
            boolean deleted = commentRepository.softDelete(commentId, nowMs);
            if (deleted) {
                publishReplyCountChanged(c.getRootId(), c.getPostId(), -1L, nowMs);
            }
            return ok(commentId, "DELETED", "已删除");
        }

        // 一级评论：同步级联删楼内回复 + 清理热榜/置顶
        commentRepository.softDelete(commentId, nowMs);
        commentRepository.softDeleteByRootId(commentId, nowMs);

        try {
            commentHotRankRepository.remove(c.getPostId(), commentId);
        } catch (Exception e) {
            log.warn("comment hot rank remove failed, postId={}, commentId={}", c.getPostId(), commentId, e);
        }

        commentPinRepository.clearIfPinned(c.getPostId(), commentId);
        return ok(commentId, "DELETED", "已删除");
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
        // 业务约束：楼内回复不允许被点赞（只允许点赞一级评论）
        if (targetTypeEnum == ReactionTargetTypeEnumVO.COMMENT) {
            CommentBriefVO c = commentRepository.getBrief(targetId);
            if (c == null || c.getStatus() == null || c.getStatus() != 1) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "评论不存在或已删除");
            }
            if (c.getRootId() != null) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "楼内回复不允许点赞");
            }
        }
        return ReactionTargetVO.builder()
                .targetType(targetTypeEnum)
                .targetId(targetId)
                .reactionType(reactionTypeEnum)
                .build();
    }

    private boolean hasDeletePermission(Long userId, CommentBriefVO c) {
        if (userId == null || c == null) {
            return false;
        }
        boolean isCommentOwner = c.getUserId() != null && c.getUserId().equals(userId);

        Long postId = c.getPostId();
        if (postId == null) {
            return isCommentOwner;
        }
        ContentPostEntity post = contentRepository.findPost(postId);
        boolean isPostOwner = post != null && post.getUserId() != null && post.getUserId().equals(userId);
        return isPostOwner || isCommentOwner;
    }

    private void publishCreated(Long commentId, Long postId, Long rootId, Long userId, Long nowMs) {
        try {
            cn.nexus.types.event.interaction.CommentCreatedEvent created = new cn.nexus.types.event.interaction.CommentCreatedEvent();
            created.setCommentId(commentId);
            created.setPostId(postId);
            created.setRootId(rootId);
            created.setUserId(userId);
            created.setCreateTimeMs(nowMs);
            commentEventPort.publish(created);
        } catch (Exception e) {
            log.warn("publish CommentCreatedEvent failed, commentId={}, postId={}, rootId={}", commentId, postId, rootId, e);
        }
    }

    private void publishReplyCountChanged(Long rootCommentId, Long postId, Long delta, Long nowMs) {
        try {
            cn.nexus.types.event.interaction.RootReplyCountChangedEvent changed = new cn.nexus.types.event.interaction.RootReplyCountChangedEvent();
            changed.setRootCommentId(rootCommentId);
            changed.setPostId(postId);
            changed.setDelta(delta);
            changed.setTsMs(nowMs);
            commentEventPort.publish(changed);
        } catch (Exception e) {
            log.warn("publish RootReplyCountChangedEvent failed, rootCommentId={}, postId={}, delta={}", rootCommentId, postId, delta, e);
        }
    }

    private void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "非法参数：" + name);
        }
    }

    private OperationResultVO ok(Long id, String status, String message) {
        return OperationResultVO.builder()
                .success(true)
                .id(id)
                .status(status)
                .message(message)
                .build();
    }

    private OperationResultVO fail(Long id, String status, String message) {
        return OperationResultVO.builder()
                .success(false)
                .id(id)
                .status(status)
                .message(message)
                .build();
    }
}
