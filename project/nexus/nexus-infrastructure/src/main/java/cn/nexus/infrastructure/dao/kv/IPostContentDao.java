package cn.nexus.infrastructure.dao.kv;

import cn.nexus.infrastructure.dao.kv.po.PostContentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IPostContentDao {

    int upsert(@Param("uuid") String uuid, @Param("content") String content);

    PostContentPO selectByUuid(@Param("uuid") String uuid);

    List<PostContentPO> selectByUuids(@Param("uuids") List<String> uuids);

    int deleteByUuid(@Param("uuid") String uuid);
}
