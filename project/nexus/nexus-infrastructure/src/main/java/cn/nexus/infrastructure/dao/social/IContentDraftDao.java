package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentDraftPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IContentDraftDao {

    int insertOrUpdate(ContentDraftPO po);

    ContentDraftPO selectById(@Param("draftId") Long draftId);
}
