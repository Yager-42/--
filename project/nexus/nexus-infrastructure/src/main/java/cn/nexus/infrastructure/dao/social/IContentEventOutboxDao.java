package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentEventOutboxPO;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IContentEventOutboxDao {

    int insertIgnore(ContentEventOutboxPO po);

    List<ContentEventOutboxPO> selectByStatus(@Param("status") String status, @Param("now") Date now, @Param("limit") int limit);

    int markSent(@Param("eventId") String eventId);

    int markFail(@Param("eventId") String eventId, @Param("nextRetryTime") Date nextRetryTime);

    int deleteOlderThan(@Param("before") Date before, @Param("status") String status);
}

