package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.CommentViewVO;
import java.util.List;

/**
 * 评论仓储接口：封装 MySQL interaction_comment 表读写。
 *
 * @author codex
 * @since 2026-01-14
 */
public interface ICommentRepository {

    CommentBriefVO getBrief(Long commentId);

    /**
     * 批量回表：查询评论详情（用于列表/热榜/楼内回复）。
     *
     * <p>不保证返回顺序；如果你要按入参 commentIds 的顺序输出，请在调用方自行重排。</p>
     */
    List<CommentViewVO> listByIds(List<Long> commentIds);

    void insert(Long commentId, Long postId, Long userId, Long rootId, Long parentId, Long replyToId, String content, Long nowMs);

    /**
     * 软删（幂等）：仅当 status=1 时更新为 2。
     *
     * @return true=本次完成从 1->2 的状态变更；false=已删除或不存在
     */
    boolean softDelete(Long commentId, Long nowMs);

    /**
     * 软删（幂等）：把某个一级评论楼内的所有回复从 status=1 改为 2。
     *
     * <p>用于“删一级评论要同步级联删二级”的上线规则；多次调用应安全。</p>
     *
     * @return true=本次至少删除了 1 条回复；false=没有需要删除的回复
     */
    boolean softDeleteByRootId(Long rootId, Long nowMs);

    void addReplyCount(Long rootCommentId, Long delta);

    void addLikeCount(Long rootCommentId, Long delta);

    /**
     * 一级评论分页（时间倒序，游标分页）。
     *
     * <p>注意：置顶不参与分页，因此 pinnedId 必须从 items 中排除；cursor 为空表示从最新开始。</p>
     */
    List<Long> pageRootCommentIds(Long postId, Long pinnedId, String cursor, int limit);

    /**
     * 楼内回复分页（时间正序，游标分页）。cursor 为空表示从最早开始。
     */
    List<Long> pageReplyCommentIds(Long rootId, String cursor, int limit);
}

