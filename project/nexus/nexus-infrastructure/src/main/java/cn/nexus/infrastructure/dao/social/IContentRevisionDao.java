package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentRevisionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IContentRevisionDao {

    int insert(ContentRevisionPO po);

    ContentRevisionPO selectOne(@Param("postId") Long postId, @Param("versionNum") Integer versionNum);

    ContentRevisionPO selectLatest(@Param("postId") Long postId);

    List<ContentRevisionPO> selectRecent(@Param("postId") Long postId, @Param("limit") Integer limit, @Param("offset") Integer offset);
}
