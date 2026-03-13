package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentHistoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IContentHistoryDao {

    int insert(ContentHistoryPO po);

    List<ContentHistoryPO> selectByPostId(@Param("postId") Long postId, @Param("limit") Integer limit, @Param("offset") Integer offset);

    ContentHistoryPO selectOne(@Param("postId") Long postId, @Param("versionNum") Integer versionNum);
}
