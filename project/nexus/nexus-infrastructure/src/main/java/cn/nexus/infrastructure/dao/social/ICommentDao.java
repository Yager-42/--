package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.CommentPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ICommentDao {

    int insert(CommentPO po);

    CommentPO selectBriefById(@Param("commentId") Long commentId);

    List<CommentPO> selectByIds(@Param("commentIds") List<Long> commentIds);

    int softDelete(@Param("commentId") Long commentId, @Param("updateTime") java.util.Date updateTime);

    int softDeleteByRootId(@Param("rootId") Long rootId, @Param("updateTime") java.util.Date updateTime);

    int approvePending(@Param("commentId") Long commentId, @Param("updateTime") java.util.Date updateTime);

    int rejectPending(@Param("commentId") Long commentId, @Param("updateTime") java.util.Date updateTime);

    int addReplyCount(@Param("commentId") Long commentId, @Param("delta") Long delta);

    int addLikeCount(@Param("commentId") Long commentId, @Param("delta") Long delta);

    List<Long> pageRootIds(@Param("postId") Long postId,
                           @Param("pinnedId") Long pinnedId,
                           @Param("cursorTime") java.util.Date cursorTime,
                           @Param("cursorId") Long cursorId,
                           @Param("limit") Integer limit);

    List<Long> pageRootIdsForViewer(@Param("postId") Long postId,
                                    @Param("pinnedId") Long pinnedId,
                                    @Param("viewerId") Long viewerId,
                                    @Param("cursorTime") java.util.Date cursorTime,
                                    @Param("cursorId") Long cursorId,
                                    @Param("limit") Integer limit);

    List<Long> pageReplyIds(@Param("rootId") Long rootId,
                            @Param("cursorTime") java.util.Date cursorTime,
                            @Param("cursorId") Long cursorId,
                            @Param("limit") Integer limit);

    List<Long> pageReplyIdsForViewer(@Param("rootId") Long rootId,
                                     @Param("viewerId") Long viewerId,
                                     @Param("cursorTime") java.util.Date cursorTime,
                                     @Param("cursorId") Long cursorId,
                                     @Param("limit") Integer limit);

    /**
     * 批量查询多个根评论的回复预览（每个 rootId 取最早的前 limit 条）。
     *
     * <p>返回的 CommentPO 只保证 rootId/commentId/createTime 有值。</p>
     */
    List<CommentPO> selectReplyPreviewIdsByRootIds(@Param("rootIds") List<Long> rootIds,
                                                   @Param("viewerId") Long viewerId,
                                                   @Param("limit") Integer limit);

    List<CommentPO> selectRecentRootBriefs(@Param("postId") Long postId, @Param("limit") Integer limit);

    /**
     * 物理清理：删除超过指定时间的软删评论（分批）。
     *
     * @param cutoff update_time 早于该时间的记录会被清理
     * @param limit  单次最多清理条数
     * @return 实际删除行数
     */
    int deleteSoftDeletedBefore(@Param("cutoff") java.util.Date cutoff, @Param("limit") Integer limit);
}
