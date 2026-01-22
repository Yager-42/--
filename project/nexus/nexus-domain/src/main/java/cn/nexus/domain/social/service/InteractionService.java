package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ICommentEventPort;
import cn.nexus.domain.social.adapter.port.IInteractionNotifyEventPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentPinRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IInteractionNotificationRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final IInteractionNotifyEventPort interactionNotifyEventPort;
    private final IInteractionNotificationRepository interactionNotificationRepository;
    private final IUserBaseRepository userBaseRepository;

    @Override
    public ReactionResultVO react(Long userId, Long targetId, String targetType, String type, String action, String requestId) {
        ReactionTargetVO target = parseTarget(targetId, targetType, type);
        ReactionActionEnumVO actionEnum = ReactionActionEnumVO.from(action);
        if (actionEnum == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        ReactionResultVO res = reactionLikeService.applyReaction(userId, target, actionEnum, requestId);

        // 评论点赞：把 delta 通过 MQ 回写到 interaction_comment.like_count，并驱动热榜刷新（最终一致）。
        if (target != null && target.getTargetType() == ReactionTargetTypeEnumVO.COMMENT) {
            Integer delta = res == null ? null : res.getDelta();
            if (delta != null && delta != 0 && targetId != null) {
                CommentBriefVO c = commentRepository.getBrief(targetId);
                if (c != null && c.getPostId() != null) {
                    publishLikeCountChanged(targetId, c.getPostId(), (long) delta, socialIdPort.now());
                }
            }
        }

        return res;
    }

    @Override
    public ReactionStateVO reactionState(Long userId, Long targetId, String targetType, String type) {
        ReactionTargetVO target = parseTarget(targetId, targetType, type);
        return reactionLikeService.queryState(userId, target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommentResultVO comment(Long userId, Long postId, Long parentId, String content) {
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
        publishNotifyCommentCreated(commentId, postId, rootId, parentIdToSave, userId, nowMs);
        publishNotifyCommentMentioned(commentId, postId, rootId, parentIdToSave, userId, nowMs, content);
        if (rootId != null) {
            publishReplyCountChanged(rootId, postId, +1L, nowMs);
        }

        // @提及：后端从 content 解析 @username 并发布 COMMENT_MENTIONED（旁路，不允许影响评论创建）。
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
        requireNonNull(userId, "userId");
        int limit = 20;
        List<NotificationVO> raw = interactionNotificationRepository.pageByUser(userId, cursor, limit);
        if (raw == null || raw.isEmpty()) {
            return NotificationListVO.builder().notifications(List.of()).nextCursor(null).build();
        }

        List<NotificationVO> items = new ArrayList<>(raw.size());
        for (NotificationVO n : raw) {
            if (n == null) {
                continue;
            }
            String bizType = n.getBizType();
            long unread = n.getUnreadCount() == null ? 0L : n.getUnreadCount();
            n.setTitle(renderTitle(bizType));
            n.setContent(renderContent(bizType, unread));
            items.add(n);
        }

        String nextCursor = null;
        if (items.size() >= limit) {
            NotificationVO last = items.get(items.size() - 1);
            nextCursor = formatCursor(last);
        }
        return NotificationListVO.builder().notifications(items).nextCursor(nextCursor).build();
    }

    @Override
    public OperationResultVO readNotification(Long userId, Long notificationId) {
        requireNonNull(userId, "userId");
        requireNonNull(notificationId, "notificationId");
        interactionNotificationRepository.markRead(userId, notificationId);
        return ok(notificationId, "READ", "已读");
    }

    @Override
    public OperationResultVO readAllNotifications(Long userId) {
        requireNonNull(userId, "userId");
        interactionNotificationRepository.markReadAll(userId);
        return ok(userId, "READ_ALL", "已全部已读");
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

    private void publishNotifyCommentCreated(Long commentId,
                                           Long postId,
                                           Long rootId,
                                           Long parentId,
                                           Long fromUserId,
                                           Long nowMs) {
        try {
            InteractionNotifyEvent event = new InteractionNotifyEvent();
            event.setEventType(EventType.COMMENT_CREATED);
            event.setEventId(commentId == null ? null : String.valueOf(commentId));
            event.setFromUserId(fromUserId);
            event.setTargetType(rootId == null ? "POST" : "COMMENT");
            event.setTargetId(rootId == null ? postId : parentId);
            event.setPostId(postId);
            event.setRootCommentId(rootId);
            event.setCommentId(commentId);
            event.setTsMs(nowMs);
            interactionNotifyEventPort.publish(event);
        } catch (Exception e) {
            log.warn("publish InteractionNotifyEvent failed, commentId={}, postId={}, rootId={}, parentId={}", commentId, postId, rootId, parentId, e);
        }
    }

    private void publishNotifyCommentMentioned(Long commentId,
                                              Long postId,
                                              Long rootId,
                                              Long parentId,
                                              Long fromUserId,
                                              Long nowMs,
                                              String content) {
        try {
            Set<String> usernames = extractMentionedUsernames(content);
            if (usernames.isEmpty()) {
                return;
            }
            List<UserBriefVO> users = userBaseRepository.listByUsernames(new ArrayList<>(usernames));
            if (users == null || users.isEmpty()) {
                return;
            }
            Set<Long> mentionedUserIds = new HashSet<>();
            for (UserBriefVO u : users) {
                if (u == null || u.getUserId() == null) {
                    continue;
                }
                if (fromUserId != null && fromUserId.equals(u.getUserId())) {
                    continue;
                }
                mentionedUserIds.add(u.getUserId());
            }
            if (mentionedUserIds.isEmpty()) {
                return;
            }

            String targetType = rootId == null ? "POST" : "COMMENT";
            Long targetId = rootId == null ? postId : parentId;
            if (targetId == null) {
                return;
            }

            for (Long toUserId : mentionedUserIds) {
                InteractionNotifyEvent event = new InteractionNotifyEvent();
                event.setEventType(EventType.COMMENT_MENTIONED);
                event.setEventId(commentId + ":" + toUserId);
                event.setFromUserId(fromUserId);
                event.setToUserId(toUserId);
                event.setTargetType(targetType);
                event.setTargetId(targetId);
                event.setPostId(postId);
                event.setRootCommentId(rootId);
                event.setCommentId(commentId);
                event.setTsMs(nowMs);
                interactionNotifyEventPort.publish(event);
            }
        } catch (Exception e) {
            log.warn("publish COMMENT_MENTIONED failed, commentId={}, postId={}, rootId={}, parentId={}", commentId, postId, rootId, parentId, e);
        }
    }

    private Set<String> extractMentionedUsernames(String content) {
        if (content == null || content.isBlank()) {
            return Set.of();
        }
        Set<String> res = new HashSet<>();
        int n = content.length();
        for (int i = 0; i < n; i++) {
            if (content.charAt(i) != '@') {
                continue;
            }
            int start = i + 1;
            if (start >= n) {
                continue;
            }
            int j = start;
            while (j < n && (j - start) < 64) {
                char ch = content.charAt(j);
                if (Character.isWhitespace(ch) || ch == '@') {
                    break;
                }
                j++;
            }
            if (j <= start) {
                continue;
            }
            String raw = content.substring(start, j);
            String username = trimTrailingPunct(raw);
            if (username != null) {
                username = username.trim();
            }
            if (username == null || username.isBlank()) {
                continue;
            }
            res.add(username);
        }
        return res;
    }

    private String trimTrailingPunct(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        int end = raw.length();
        while (end > 0) {
            char c = raw.charAt(end - 1);
            if (c == ',' || c == '.' || c == ';' || c == ':' || c == '!' || c == '?' || c == ')' || c == ']' || c == '}'
                    || c == '，' || c == '。' || c == '！' || c == '？' || c == '）' || c == '】' || c == '》') {
                end--;
                continue;
            }
            break;
        }
        return end <= 0 ? "" : raw.substring(0, end);
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

    private void publishLikeCountChanged(Long rootCommentId, Long postId, Long delta, Long nowMs) {
        try {
            cn.nexus.types.event.interaction.CommentLikeChangedEvent changed = new cn.nexus.types.event.interaction.CommentLikeChangedEvent();
            changed.setRootCommentId(rootCommentId);
            changed.setPostId(postId);
            changed.setDelta(delta);
            changed.setTsMs(nowMs);
            commentEventPort.publish(changed);
        } catch (Exception e) {
            log.warn("publish CommentLikeChangedEvent failed, rootCommentId={}, postId={}, delta={}", rootCommentId, postId, delta, e);
        }
    }

    private String renderTitle(String bizType) {
        if (bizType == null) {
            return "通知";
        }
        return switch (bizType) {
            case "POST_LIKED" -> "帖子获赞";
            case "COMMENT_LIKED" -> "评论获赞";
            case "POST_COMMENTED" -> "帖子被评论";
            case "COMMENT_REPLIED" -> "评论被回复";
            case "COMMENT_MENTIONED" -> "提及你";
            default -> "通知";
        };
    }

    private String renderContent(String bizType, long unreadCount) {
        long n = Math.max(0L, unreadCount);
        if (bizType == null) {
            return "你有新的互动";
        }
        return switch (bizType) {
            case "POST_LIKED" -> "你的帖子新增 " + n + " 个赞";
            case "COMMENT_LIKED" -> "你的评论新增 " + n + " 个赞";
            case "POST_COMMENTED" -> "你的帖子新增 " + n + " 条评论";
            case "COMMENT_REPLIED" -> "你的评论新增 " + n + " 条回复";
            case "COMMENT_MENTIONED" -> "有人在评论里提及你 " + n + " 次";
            default -> "你有新的互动";
        };
    }

    private String formatCursor(NotificationVO last) {
        if (last == null || last.getCreateTime() == null || last.getNotificationId() == null) {
            return null;
        }
        return last.getCreateTime() + ":" + last.getNotificationId();
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
