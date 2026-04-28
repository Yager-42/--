package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.PostCounterProjectionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IPostCounterProjectionDao {

    PostCounterProjectionPO selectByPostId(@Param("postId") Long postId);

    int insert(PostCounterProjectionPO po);

    int updateState(@Param("postId") Long postId,
                    @Param("projectedPublished") Integer projectedPublished,
                    @Param("lastEventId") Long lastEventId);
}
