package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RelationEventOutboxPO;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IRelationEventOutboxDao {

    int insertIgnore(RelationEventOutboxPO po);

    List<RelationEventOutboxPO> selectByStatus(@Param("status") String status, @Param("now") Date now, @Param("limit") int limit);

    int markSent(@Param("eventId") Long eventId);

    int markFail(@Param("eventId") Long eventId, @Param("nextRetryTime") Date nextRetryTime);

    int deleteOlderThan(@Param("before") Date before, @Param("status") String status);
}
