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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 互动服务。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
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
    private final IRiskService riskService;
    private final ICommentEventPort commentEventPort;
    private final IInteractionNotifyEventPort interactionNotifyEventPort;
    private final IInteractionNotificationRepository interactionNotificationRepository;
    private final IUserBaseRepository userBaseRepository;

    private static final int COMMENT_STATUS_PENDING_REVIEW = 0;
    private static final int COMMENT_STATUS_NORMAL = 1;

    /**
     * 统一点赞/取消点赞入口。
     *
     * @param userId 操作人 ID，类型：{@link Long}
     * @param targetId 目标 ID，类型：{@link Long}
     * @param targetType 目标类型，类型：{@link String}
     * @param type 互动类型，类型：{@link String}
     * @param action 动作类型，类型：{@link String}
     * @param requestId 请求幂等号，类型：{@link String}
     * @return 点赞执行结果，类型：{@link ReactionResultVO}
     */
    @Override
    public ReactionResultVO react(Long userId, Long targetId, String targetType, String type, String action, String requestId) {
        ReactionTargetVO target = parseTarget(targetId, targetType, type);
        ReactionActionEnumVO actionEnum = ReactionActionEnumVO.from(action);
        if (actionEnum == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        return reactionLikeService.applyReaction(userId, target, actionEnum, requestId);

        // 评论点赞的计数不是同步写回主表，而是发事件让聚合链路最终一致收敛。
    }

    /**
     * 查询当前用户的点赞状态。
     *
     * @param userId 当前用户 ID，类型：{@link Long}
     * @param targetId 目标 ID，类型：{@link Long}
     * @param targetType 目标类型，类型：{@link String}
     * @param type 互动类型，类型：{@link String}
     * @return 点赞状态结果，类型：{@link ReactionStateVO}
     */
    @Override
    public ReactionStateVO reactionState(Long userId, Long targetId, String targetType, String type) {
        ReactionTargetVO target = parseTarget(targetId, targetType, type);
        return reactionLikeService.queryState(userId, target);
    }

    /**
     * 创建评论或楼内回复。
     *
     * @param userId 评论人 ID，类型：{@link Long}
     * @param postId 帖子 ID，类型：{@link Long}
     * @param parentId 父评论 ID；一级评论时可为 `null`，类型：{@link Long}
     * @param content 评论内容，类型：{@link String}
     * @param commentId 指定评论 ID；为空时自动生成，类型：{@link Long}
     * @return 评论执行结果，类型：{@link CommentResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommentResultVO comment(Long userId, Long postId, Long parentId, String content, Long commentId) {
        requireNonNull(userId, "userId");
        requireNonNull(postId, "postId");

        Long nowMs = socialIdPort.now();
        Long cid = commentId == null ? socialIdPort.nextId() : commentId;

        Long rootId = null;
        Long parentIdToSave = null;
        Long replyToId = null;

        if (parentId != null) {
            // 回复场景先把父评论校验清楚，再把 root / parent / replyTo 三个 ID 一次性算准。
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

        RiskEventVO riskEvent = RiskEventVO.builder()
                .eventId(String.valueOf(cid))
                .userId(userId)
                .actionType("COMMENT_CREATE")
                .scenario("comment.create")
                .contentText(content)
                .mediaUrls(List.of())
                .targetId(String.valueOf(postId))
                .extJson("{\"biz\":\"comment\",\"commentId\":" + cid + ",\"postId\":" + postId + ",\"parentId\":" + (parentId == null ? "null" : parentId) + "}")
                .occurTime(nowMs)
                .build();
        // 评论先过风控，再决定直接发布还是进入待审核。
        RiskDecisionVO decision = riskService.decision(riskEvent);
        String riskResult = decision == null ? "PASS" : decision.getResult();
        if ("BLOCK".equalsIgnoreCase(riskResult) || "LIMIT".equalsIgnoreCase(riskResult) || "CHALLENGE".equalsIgnoreCase(riskResult)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "风控拦截");
        }

        int status = "REVIEW".equalsIgnoreCase(riskResult) ? COMMENT_STATUS_PENDING_REVIEW : COMMENT_STATUS_NORMAL;
        commentRepository.insert(cid, postId, userId, rootId, parentIdToSave, replyToId, content, status, nowMs);
        if (status == COMMENT_STATUS_PENDING_REVIEW) {
            return CommentResultVO.builder().commentId(cid).createTime(nowMs).status("PENDING_REVIEW").build();
        }

        publishCreated(cid, postId, rootId, userId, nowMs);
        publishNotifyCommentCreated(cid, postId, rootId, parentIdToSave, userId, nowMs);
        publishNotifyCommentMentioned(cid, postId, rootId, parentIdToSave, userId, nowMs, content);
        if (rootId != null) {
            publishReplyCountChanged(rootId, postId, +1L, nowMs);
        }

        // `@username` 提及走旁路事件；即使发布失败，也不能反向拖垮评论主流程。
        return CommentResultVO.builder().commentId(cid).createTime(nowMs).status("OK").build();
    }

    /**
     * 应用评论风控复审结果。
     *
     * @param commentId 评论 ID，类型：{@link Long}
     * @param finalResult 复审结果，类型：{@link String}
     * @param reasonCode 原因码，类型：{@link String}
     * @return 处理结果，类型：{@link OperationResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO applyCommentRiskReviewResult(Long commentId, String finalResult, String reasonCode) {
        requireNonNull(commentId, "commentId");
        if (finalResult == null || finalResult.isBlank() || "REVIEW".equalsIgnoreCase(finalResult)) {
            return ok(commentId, "SKIP", "仍需审核");
        }

        List<CommentViewVO> list = commentRepository.listByIds(List.of(commentId));
        CommentViewVO c = list == null || list.isEmpty() ? null : list.get(0);
        if (c == null || c.getStatus() == null || c.getStatus() != COMMENT_STATUS_PENDING_REVIEW) {
            return ok(commentId, "SKIP", "非待审核状态");
        }

        Long nowMs = socialIdPort.now();
        if ("PASS".equalsIgnoreCase(finalResult)) {
            // 复审通过后要补齐“正式发布”阶段该发的所有事件，保证审核前后行为一致。
            boolean approved = commentRepository.approvePending(commentId, nowMs);
            if (!approved) {
                return ok(commentId, "SKIP", "已处理");
            }
            publishCreated(commentId, c.getPostId(), c.getRootId(), c.getUserId(), nowMs);
            publishNotifyCommentCreated(commentId, c.getPostId(), c.getRootId(), c.getParentId(), c.getUserId(), nowMs);
            publishNotifyCommentMentioned(commentId, c.getPostId(), c.getRootId(), c.getParentId(), c.getUserId(), nowMs, c.getContent());
            if (c.getRootId() != null) {
                publishReplyCountChanged(c.getRootId(), c.getPostId(), +1L, nowMs);
            }
            return ok(commentId, "APPROVED", "已通过");
        }

        if ("BLOCK".equalsIgnoreCase(finalResult)) {
            commentRepository.rejectPending(commentId, nowMs);
            return ok(commentId, "REJECTED", "已拒绝");
        }

        return ok(commentId, "SKIP", "未知结论");
    }

    /**
     * 置顶一级评论。
     *
     * @param userId 操作人 ID，类型：{@link Long}
     * @param commentId 评论 ID，类型：{@link Long}
     * @param postId 帖子 ID，类型：{@link Long}
     * @return 处理结果，类型：{@link OperationResultVO}
     */
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

    /**
     * 删除评论。
     *
     * @param userId 操作人 ID，类型：{@link Long}
     * @param commentId 评论 ID，类型：{@link Long}
     * @return 处理结果，类型：{@link OperationResultVO}
     */
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

        // 回复删除只影响本楼的 reply_count，不需要清热榜和置顶。
        if (c.getRootId() != null) {
            boolean deleted = commentRepository.softDelete(commentId, nowMs);
            if (deleted) {
                publishReplyCountChanged(c.getRootId(), c.getPostId(), -1L, nowMs);
            }
            return ok(commentId, "DELETED", "已删除");
        }

        // 一级评论删除要顺手级联清楼内回复，并把热榜和置顶状态一起回收。
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

    /**
     * 查询通知聚合列表。
     *
     * @param userId 当前用户 ID，类型：{@link Long}
     * @param cursor 翻页游标，类型：{@link String}
     * @return 通知列表结果，类型：{@link NotificationListVO}
     */
    @Override
    public NotificationListVO notifications(Long userId, String cursor) {
        requireNonNull(userId, "userId");
        int limit = 20;
        List<NotificationVO> raw = interactionNotificationRepository.pageByUser(userId, cursor, limit);
        if (raw == null || raw.isEmpty()) {
            return NotificationListVO.builder().notifications(List.of()).nextCursor(null).build();
        }

        // 标题和摘要不直接存库，查询时按业务类型动态渲染，减少写链路冗余。
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

    /**
     * 标记单条通知已读。
     *
     * @param userId 当前用户 ID，类型：{@link Long}
     * @param notificationId 通知 ID，类型：{@link Long}
     * @return 处理结果，类型：{@link OperationResultVO}
     */
    @Override
    public OperationResultVO readNotification(Long userId, Long notificationId) {
        requireNonNull(userId, "userId");
        requireNonNull(notificationId, "notificationId");
        interactionNotificationRepository.markRead(userId, notificationId);
        return ok(notificationId, "READ", "已读");
    }

    /**
     * 标记当前用户的全部通知已读。
     *
     * @param userId 当前用户 ID，类型：{@link Long}
     * @return 处理结果，类型：{@link OperationResultVO}
     */
    @Override
    public OperationResultVO readAllNotifications(Long userId) {
        requireNonNull(userId, "userId");
        interactionNotificationRepository.markReadAll(userId);
        return ok(userId, "READ_ALL", "已全部已读");
    }

    private ReactionTargetVO parseTarget(Long targetId, String targetType, String type) {
        ReactionTargetTypeEnumVO targetTypeEnum = ReactionTargetTypeEnumVO.from(targetType);
        ReactionTypeEnumVO reactionTypeEnum = ReactionTypeEnumVO.from(type);
        if (targetId == null || targetTypeEnum == null || reactionTypeEnum == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        // 楼内回复不开放点赞，避免把评论树的互动模型越做越复杂。
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

            // 每个被提及的人发一条独立事件，消费侧才能按收件人做幂等和聚合。
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
