package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentChunkPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IContentChunkDao {

    int insertIgnore(ContentChunkPO po);

    ContentChunkPO selectByHash(@Param("chunkHash") String chunkHash);
}
