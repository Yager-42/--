package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.CommentViewVO;
import java.util.Date;
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

    void insert(Long commentId, Long postId, Long userId, Long rootId, Long parentId, Long replyToId, String content, Integer status, Long nowMs);

    /**
     * 待审核评论通过：仅当 status=0 时更新为 1。
     */
    boolean approvePending(Long commentId, Long nowMs);

    /**
     * 待审核评论拒绝：仅当 status=0 时更新为 2（软删）。
     */
    boolean rejectPending(Long commentId, Long nowMs);

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

    /**
     * 物理清理：删除超过指定时间的软删评论（分批）。
     *
     * @param cutoff update_time 早于该时间的记录会被清理
     * @param limit  单次最多清理条数
     * @return 实际删除行数
     */
    int deleteSoftDeletedBefore(Date cutoff, int limit);

    void addReplyCount(Long rootCommentId, Long delta);

    void addLikeCount(Long rootCommentId, Long delta);

    /**
     * 一级评论分页（时间倒序，游标分页）。
     *
     * <p>注意：置顶不参与分页，因此 pinnedId 必须从 items 中排除；cursor 为空表示从最新开始。</p>
     */
    List<Long> pageRootCommentIds(Long postId, Long pinnedId, String cursor, int limit, Long viewerId);

    /**
     * 楼内回复分页（时间正序，游标分页）。cursor 为空表示从最早开始。
     */
    List<Long> pageReplyCommentIds(Long rootId, String cursor, int limit, Long viewerId);

    /**
     * 扫描某帖最近的一级评论（用于热榜冷启动/重建）。
     *
     * <p>只返回 status=1 且 root_id IS NULL 的评论。</p>
     *
     * @param postId 帖子 ID
     * @param limit  扫描上限（建议 5000）
     * @return 一级评论简要信息列表（包含 like_count/reply_count）
     */
    List<CommentBriefVO> listRecentRootBriefs(Long postId, int limit);
}
