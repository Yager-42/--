package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.ICommentPinRepository;
import cn.nexus.infrastructure.dao.social.ICommentPinDao;
import cn.nexus.infrastructure.dao.social.po.CommentPinPO;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 评论置顶仓储 MyBatis 实现（单帖单置顶）。
 *
 * @author codex
 * @since 2026-01-14
 */
@Repository
@RequiredArgsConstructor
public class CommentPinRepository implements ICommentPinRepository {

    private final ICommentPinDao commentPinDao;

    @Override
    public Long getPinnedCommentId(Long postId) {
        if (postId == null) {
            return null;
        }
        CommentPinPO po = commentPinDao.selectByPostId(postId);
        return po == null ? null : po.getCommentId();
    }

    @Override
    public void pin(Long postId, Long commentId, Long nowMs) {
        if (postId == null || commentId == null) {
            return;
        }
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        CommentPinPO po = new CommentPinPO();
        po.setPostId(postId);
        po.setCommentId(commentId);
        po.setCreateTime(now);
        po.setUpdateTime(now);
        commentPinDao.insertOrUpdate(po);
    }

    @Override
    public void clear(Long postId) {
        if (postId == null) {
            return;
        }
        commentPinDao.deleteByPostId(postId);
    }

    @Override
    public void clearIfPinned(Long postId, Long commentId) {
        if (postId == null || commentId == null) {
            return;
        }
        commentPinDao.deleteByPostIdAndCommentId(postId, commentId);
    }
}

