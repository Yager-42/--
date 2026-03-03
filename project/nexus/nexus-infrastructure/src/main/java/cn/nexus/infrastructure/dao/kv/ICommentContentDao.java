package cn.nexus.infrastructure.dao.kv;

import cn.nexus.infrastructure.dao.kv.po.CommentContentKeyPO;
import cn.nexus.infrastructure.dao.kv.po.CommentContentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ICommentContentDao {

    int batchUpsert(@Param("comments") List<CommentContentPO> comments);

    List<CommentContentPO> batchSelect(@Param("postId") Long postId, @Param("keys") List<CommentContentKeyPO> keys);

    int delete(@Param("postId") Long postId, @Param("yearMonth") String yearMonth, @Param("contentId") String contentId);
}
