package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RelationEventInboxPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IRelationEventInboxDao {

    int insertIgnore(RelationEventInboxPO po);

    int updateStatus(@Param("fingerprint") String fingerprint, @Param("status") String status);

    java.util.List<RelationEventInboxPO> selectByStatus(@Param("status") String status, @Param("limit") int limit);

    int deleteOlderThan(@Param("before") java.util.Date before, @Param("status") String status);
}
