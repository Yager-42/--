package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentPatchPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IContentPatchDao {

    int insertIgnore(ContentPatchPO po);

    ContentPatchPO selectByHash(@Param("patchHash") String patchHash);
}
