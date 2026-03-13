package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentPinRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.valobj.CommentHotVO;
import cn.nexus.domain.social.model.valobj.CommentViewVO;
import cn.nexus.domain.social.model.valobj.ReplyCommentPageVO;
import cn.nexus.domain.social.model.valobj.RootCommentPageVO;
import cn.nexus.domain.social.model.valobj.RootCommentViewVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 评论读侧查询服务。
 *
 * <p>负责把评论主干、回复预览、热榜和用户昵称头像拼成前端可以直接消费的结果。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-01-21
 */
@Service
@RequiredArgsConstructor
public class CommentQueryService implements ICommentQueryService {

    private final ICommentRepository commentRepository;
    private final ICommentPinRepository commentPinRepository;
    private final ICommentHotRankRepository commentHotRankRepository;
    private final IUserBaseRepository userBaseRepository;

    /**
     * 查询一级评论列表，并尽量顺手补齐置顶评论和回复预览。
     *
     * @param postId 帖子 ID，类型：{@link Long}
     * @param viewerId 查看者 ID；匿名可为 `null`，类型：{@link Long}
     * @param cursor 翻页游标，类型：{@link String}
     * @param limit 页大小，类型：{@link Integer}
     * @param preloadReplyLimit 每条根评论预加载的回复数，类型：{@link Integer}
     * @return 一级评论分页结果，类型：{@link RootCommentPageVO}
     */
    @Override
    public RootCommentPageVO listRootComments(Long postId, Long viewerId, String cursor, Integer limit, Integer preloadReplyLimit) {
        requireNonNull(postId, "postId");
        int normalizedLimit = normalizeLimit(limit, 20, 50);
        int preload = normalizePreload(preloadReplyLimit, 3, 10);

        // 置顶评论和普通评论走两条读取路径，避免置顶项被分页逻辑吞掉。
        Long pinnedId = commentPinRepository.getPinnedCommentId(postId);
        RootCommentViewVO pinned = loadPinned(postId, pinnedId, preload, viewerId);

        List<Long> rootIds = commentRepository.pageRootCommentIds(postId, pinnedId, cursor, normalizedLimit, viewerId);
        List<RootCommentViewVO> items = loadRootsWithPreview(rootIds, preload, viewerId);

        enrichUserProfile(pinned, items);

        String nextCursor = null;
        if (rootIds != null && rootIds.size() >= normalizedLimit) {
            CommentViewVO last = lastRoot(items);
            nextCursor = last == null ? null : formatCursor(last);
        }

        return RootCommentPageVO.builder()
                .pinned(pinned)
                .items(items)
                .nextCursor(nextCursor)
                .build();
    }

    /**
     * 查询某条一级评论下的回复列表。
     *
     * @param rootId 根评论 ID，类型：{@link Long}
     * @param viewerId 查看者 ID；匿名可为 `null`，类型：{@link Long}
     * @param cursor 翻页游标，类型：{@link String}
     * @param limit 页大小，类型：{@link Integer}
     * @return 回复分页结果，类型：{@link ReplyCommentPageVO}
     */
    @Override
    public ReplyCommentPageVO listReplies(Long rootId, Long viewerId, String cursor, Integer limit) {
        requireNonNull(rootId, "rootId");
        int normalizedLimit = normalizeLimit(limit, 50, 100);

        List<Long> ids = commentRepository.pageReplyCommentIds(rootId, cursor, normalizedLimit, viewerId);
        List<CommentViewVO> items = loadComments(ids);
        if (!items.isEmpty()) {
            // 查看者不可见的回复要在这里过滤掉，避免把后续资料补齐浪费在无效行上。
            List<CommentViewVO> visible = new ArrayList<>(items.size());
            for (CommentViewVO v : items) {
                if (visibleToViewer(v, viewerId)) {
                    visible.add(v);
                }
            }
            items = visible;
        }
        enrichUserProfile(items);

        String nextCursor = null;
        if (ids != null && ids.size() >= normalizedLimit) {
            CommentViewVO last = last(items);
            nextCursor = last == null ? null : formatCursor(last);
        }
        return ReplyCommentPageVO.builder().items(items).nextCursor(nextCursor).build();
    }

    /**
     * 查询评论热榜。
     *
     * @param postId 帖子 ID，类型：{@link Long}
     * @param limit 返回条数上限，类型：{@link Integer}
     * @param preloadReplyLimit 每条根评论预加载的回复数，类型：{@link Integer}
     * @return 评论热榜结果，类型：{@link CommentHotVO}
     */
    @Override
    public CommentHotVO hotComments(Long postId, Integer limit, Integer preloadReplyLimit) {
        requireNonNull(postId, "postId");
        int normalizedLimit = normalizeLimit(limit, 20, 50);
        int preload = normalizePreload(preloadReplyLimit, 3, 10);

        Long pinnedId = commentPinRepository.getPinnedCommentId(postId);
        RootCommentViewVO pinned = loadPinned(postId, pinnedId, preload, null);

        int fetchCount = normalizedLimit + 1;
        List<Long> raw = commentHotRankRepository.topIds(postId, fetchCount);
        List<Long> hotIds = new ArrayList<>();
        if (raw != null) {
            // 热榜里如果混进置顶评论，要主动去掉，避免前端重复展示。
            for (Long id : raw) {
                if (id == null) {
                    continue;
                }
                if (pinnedId != null && pinnedId.equals(id)) {
                    continue;
                }
                hotIds.add(id);
            }
        }
        if (hotIds.size() > normalizedLimit) {
            hotIds = hotIds.subList(0, normalizedLimit);
        }

        List<RootCommentViewVO> items = loadRootsWithPreview(hotIds, preload, null);
        enrichUserProfile(pinned, items);

        return CommentHotVO.builder().pinned(pinned).items(items).build();
    }

    private RootCommentViewVO loadPinned(Long postId, Long pinnedId, int preload, Long viewerId) {
        if (postId == null || pinnedId == null) {
            return null;
        }
        List<CommentViewVO> list = loadComments(List.of(pinnedId));
        if (list.isEmpty()) {
            commentPinRepository.clear(postId);
            return null;
        }
        CommentViewVO root = list.get(0);
        if (root == null
                || root.getPostId() == null
                || !postId.equals(root.getPostId())
                || root.getRootId() != null
                || root.getStatus() == null
                || root.getStatus() != 1) {
            commentPinRepository.clear(postId);
            return null;
        }

        List<CommentViewVO> preview = loadRepliesPreview(root.getCommentId(), preload, viewerId);
        return RootCommentViewVO.builder().root(root).repliesPreview(preview).build();
    }

    private List<RootCommentViewVO> loadRootsWithPreview(List<Long> rootIds, int preload, Long viewerId) {
        if (rootIds == null || rootIds.isEmpty()) {
            return List.of();
        }
        List<CommentViewVO> roots = loadComments(rootIds);
        if (roots.isEmpty()) {
            return List.of();
        }
        List<RootCommentViewVO> res = new ArrayList<>(roots.size());
        for (CommentViewVO root : roots) {
            if (root == null) {
                continue;
            }
            // 热榜可能有脏 ID：只返回一级评论；待审核评论仅对作者本人可见
            if (root.getRootId() != null) {
                continue;
            }
            if (!visibleToViewer(root, viewerId)) {
                continue;
            }
            List<CommentViewVO> preview = loadRepliesPreview(root.getCommentId(), preload, viewerId);
            res.add(RootCommentViewVO.builder().root(root).repliesPreview(preview).build());
        }
        return res;
    }

    private List<CommentViewVO> loadRepliesPreview(Long rootCommentId, int preload, Long viewerId) {
        if (rootCommentId == null || preload <= 0) {
            return List.of();
        }
        List<Long> replyIds = commentRepository.pageReplyCommentIds(rootCommentId, null, preload, viewerId);
        List<CommentViewVO> list = loadComments(replyIds);
        if (list.isEmpty()) {
            return List.of();
        }
        // 防止“预览缓存 key 不区分 viewerId”导致待审核(status=0)回复被其他人看见：这里统一做可见性过滤。
        List<CommentViewVO> visible = new ArrayList<>(list.size());
        for (CommentViewVO v : list) {
            if (visibleToViewer(v, viewerId)) {
                visible.add(v);
            }
        }
        return visible;
    }

    private List<CommentViewVO> loadComments(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<CommentViewVO> list = commentRepository.listByIds(ids);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Map<Long, CommentViewVO> map = new HashMap<>(list.size() * 2);
        for (CommentViewVO vo : list) {
            if (vo == null || vo.getCommentId() == null) {
                continue;
            }
            map.put(vo.getCommentId(), vo);
        }
        List<CommentViewVO> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            CommentViewVO vo = map.get(id);
            if (vo != null) {
                ordered.add(vo);
            }
        }
        return ordered;
    }

    private void enrichUserProfile(RootCommentViewVO pinned, List<RootCommentViewVO> roots) {
        Set<Long> userIds = new HashSet<>();
        collectUserIds(pinned, userIds);
        if (roots != null) {
            for (RootCommentViewVO v : roots) {
                collectUserIds(v, userIds);
            }
        }
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, UserBriefVO> map = toMap(userBaseRepository.listByUserIds(new ArrayList<>(userIds)));
        applyUserProfile(pinned, map);
        if (roots != null) {
            for (RootCommentViewVO v : roots) {
                applyUserProfile(v, map);
            }
        }
    }

    private void enrichUserProfile(List<CommentViewVO> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<Long> userIds = new HashSet<>();
        for (CommentViewVO v : items) {
            if (v != null && v.getUserId() != null) {
                userIds.add(v.getUserId());
            }
        }
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, UserBriefVO> map = toMap(userBaseRepository.listByUserIds(new ArrayList<>(userIds)));
        for (CommentViewVO v : items) {
            applyUserProfile(v, map);
        }
    }

    private void collectUserIds(RootCommentViewVO v, Set<Long> userIds) {
        if (v == null || userIds == null) {
            return;
        }
        CommentViewVO root = v.getRoot();
        if (root != null && root.getUserId() != null) {
            userIds.add(root.getUserId());
        }
        if (v.getRepliesPreview() != null) {
            for (CommentViewVO c : v.getRepliesPreview()) {
                if (c != null && c.getUserId() != null) {
                    userIds.add(c.getUserId());
                }
            }
        }
    }

    private Map<Long, UserBriefVO> toMap(List<UserBriefVO> list) {
        if (list == null || list.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserBriefVO> map = new HashMap<>(list.size() * 2);
        for (UserBriefVO u : list) {
            if (u == null || u.getUserId() == null) {
                continue;
            }
            map.put(u.getUserId(), u);
        }
        return map;
    }

    private void applyUserProfile(RootCommentViewVO v, Map<Long, UserBriefVO> map) {
        if (v == null) {
            return;
        }
        applyUserProfile(v.getRoot(), map);
        if (v.getRepliesPreview() != null) {
            for (CommentViewVO c : v.getRepliesPreview()) {
                applyUserProfile(c, map);
            }
        }
    }

    private void applyUserProfile(CommentViewVO v, Map<Long, UserBriefVO> map) {
        if (v == null || v.getUserId() == null || map == null || map.isEmpty()) {
            return;
        }
        UserBriefVO u = map.get(v.getUserId());
        if (u == null) {
            return;
        }
        v.setNickname(u.getNickname());
        v.setAvatarUrl(u.getAvatarUrl());
    }

    private boolean visibleToViewer(CommentViewVO v, Long viewerId) {
        if (v == null) {
            return false;
        }
        Integer st = v.getStatus();
        if (st != null && st == 1) {
            return true;
        }
        return st != null && st == 0 && viewerId != null && viewerId.equals(v.getUserId());
    }

    private CommentViewVO lastRoot(List<RootCommentViewVO> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            RootCommentViewVO v = items.get(i);
            if (v != null && v.getRoot() != null) {
                return v.getRoot();
            }
        }
        return null;
    }

    private CommentViewVO last(List<CommentViewVO> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            CommentViewVO v = items.get(i);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private String formatCursor(CommentViewVO last) {
        if (last == null || last.getCreateTime() == null || last.getCommentId() == null) {
            return null;
        }
        return last.getCreateTime() + ":" + last.getCommentId();
    }

    private int normalizeLimit(Integer limit, int defaultLimit, int max) {
        if (limit == null) {
            return defaultLimit;
        }
        int v = limit;
        if (v <= 0) {
            return defaultLimit;
        }
        return Math.min(v, max);
    }

    private int normalizePreload(Integer preload, int defaultPreload, int max) {
        if (preload == null) {
            return defaultPreload;
        }
        int v = preload;
        if (v < 0) {
            return defaultPreload;
        }
        return Math.min(v, max);
    }

    private void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "非法参数：" + name);
        }
    }
}
