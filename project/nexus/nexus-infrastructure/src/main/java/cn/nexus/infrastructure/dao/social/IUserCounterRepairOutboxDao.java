package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.UserCounterRepairOutboxPO;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IUserCounterRepairOutboxDao {

    int insertIgnore(UserCounterRepairOutboxPO po);

    List<UserCounterRepairOutboxPO> selectByStatus(@Param("status") String status,
                                                   @Param("now") Date now,
                                                   @Param("limit") int limit);

    int markDone(@Param("id") Long id);

    int markFail(@Param("id") Long id, @Param("nextRetryTime") Date nextRetryTime);
}
