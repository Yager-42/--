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

    int addReplyCount(@Param("commentId") Long commentId, @Param("delta") Long delta);

    int addLikeCount(@Param("commentId") Long commentId, @Param("delta") Long delta);

    List<Long> pageRootIds(@Param("postId") Long postId,
                           @Param("pinnedId") Long pinnedId,
                           @Param("cursorTime") java.util.Date cursorTime,
                           @Param("cursorId") Long cursorId,
                           @Param("limit") Integer limit);

    List<Long> pageReplyIds(@Param("rootId") Long rootId,
                            @Param("cursorTime") java.util.Date cursorTime,
                            @Param("cursorId") Long cursorId,
                            @Param("limit") Integer limit);

    List<CommentPO> selectRecentRootBriefs(@Param("postId") Long postId, @Param("limit") Integer limit);
}
