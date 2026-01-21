package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.CommentPinPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ICommentPinDao {

    CommentPinPO selectByPostId(@Param("postId") Long postId);

    int insertOrUpdate(CommentPinPO po);

    int deleteByPostId(@Param("postId") Long postId);

    int deleteByPostIdAndCommentId(@Param("postId") Long postId, @Param("commentId") Long commentId);
}

