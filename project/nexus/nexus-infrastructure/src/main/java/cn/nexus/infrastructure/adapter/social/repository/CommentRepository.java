package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.CommentViewVO;
import cn.nexus.infrastructure.dao.social.ICommentDao;
import cn.nexus.infrastructure.dao.social.po.CommentPO;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 评论仓储 MyBatis 实现。
 *
 * @author codex
 * @since 2026-01-14
 */
@Repository
@RequiredArgsConstructor
public class CommentRepository implements ICommentRepository {

    private final ICommentDao commentDao;

    @Override
    public CommentBriefVO getBrief(Long commentId) {
        if (commentId == null) {
            return null;
        }
        CommentPO po = commentDao.selectBriefById(commentId);
        if (po == null) {
            return null;
        }
        return CommentBriefVO.builder()
                .commentId(po.getCommentId())
                .postId(po.getPostId())
                .userId(po.getUserId())
                .rootId(po.getRootId())
                .status(po.getStatus())
                .likeCount(po.getLikeCount())
                .replyCount(po.getReplyCount())
                .build();
    }

    @Override
    public List<CommentViewVO> listByIds(List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return List.of();
        }
        List<CommentPO> list = commentDao.selectByIds(commentIds);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<CommentViewVO> res = new ArrayList<>(list.size());
        for (CommentPO po : list) {
            if (po == null) {
                continue;
            }
            Date ct = po.getCreateTime();
            res.add(CommentViewVO.builder()
                    .commentId(po.getCommentId())
                    .postId(po.getPostId())
                    .userId(po.getUserId())
                    .rootId(po.getRootId())
                    .parentId(po.getParentId())
                    .replyToId(po.getReplyToId())
                    .content(po.getContent())
                    .status(po.getStatus())
                    .likeCount(po.getLikeCount())
                    .replyCount(po.getReplyCount())
                    .createTime(ct == null ? null : ct.getTime())
                    .build());
        }
        return res;
    }

    @Override
    public void insert(Long commentId, Long postId, Long userId, Long rootId, Long parentId, Long replyToId, String content, Long nowMs) {
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        CommentPO po = new CommentPO();
        po.setCommentId(commentId);
        po.setPostId(postId);
        po.setUserId(userId);
        po.setRootId(rootId);
        po.setParentId(parentId);
        po.setReplyToId(replyToId);
        po.setContent(content == null ? "" : content);
        po.setStatus(1);
        po.setLikeCount(0L);
        po.setReplyCount(0L);
        po.setCreateTime(now);
        po.setUpdateTime(now);
        commentDao.insert(po);
    }

    @Override
    public boolean softDelete(Long commentId, Long nowMs) {
        if (commentId == null) {
            return false;
        }
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        int affected = commentDao.softDelete(commentId, now);
        return affected > 0;
    }

    @Override
    public boolean softDeleteByRootId(Long rootId, Long nowMs) {
        if (rootId == null) {
            return false;
        }
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        int affected = commentDao.softDeleteByRootId(rootId, now);
        return affected > 0;
    }

    @Override
    public void addReplyCount(Long rootCommentId, Long delta) {
        if (rootCommentId == null || delta == null || delta == 0) {
            return;
        }
        commentDao.addReplyCount(rootCommentId, delta);
    }

    @Override
    public void addLikeCount(Long rootCommentId, Long delta) {
        if (rootCommentId == null || delta == null || delta == 0) {
            return;
        }
        commentDao.addLikeCount(rootCommentId, delta);
    }

    @Override
    public List<Long> pageRootCommentIds(Long postId, Long pinnedId, String cursor, int limit) {
        Cursor c = Cursor.parse(cursor);
        int normalizedLimit = Math.max(1, limit);
        return commentDao.pageRootIds(postId,
                pinnedId,
                c == null ? null : c.cursorTime,
                c == null ? null : c.cursorId,
                normalizedLimit);
    }

    @Override
    public List<Long> pageReplyCommentIds(Long rootId, String cursor, int limit) {
        Cursor c = Cursor.parse(cursor);
        int normalizedLimit = Math.max(1, limit);
        return commentDao.pageReplyIds(rootId,
                c == null ? null : c.cursorTime,
                c == null ? null : c.cursorId,
                normalizedLimit);
    }

    private static final class Cursor {
        private final Date cursorTime;
        private final Long cursorId;

        private Cursor(Date cursorTime, Long cursorId) {
            this.cursorTime = cursorTime;
            this.cursorId = cursorId;
        }

        private static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            String[] parts = cursor.split(":");
            if (parts.length != 2) {
                return null;
            }
            try {
                long timeMs = Long.parseLong(parts[0]);
                long id = Long.parseLong(parts[1]);
                return new Cursor(new Date(timeMs), id);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}

