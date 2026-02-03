package cn.nexus.infrastructure.dao.user;

import cn.nexus.infrastructure.dao.user.po.UserEventOutboxPO;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IUserEventOutboxDao {

    int insertIgnore(UserEventOutboxPO po);

    List<UserEventOutboxPO> selectByStatus(@Param("status") String status, @Param("limit") int limit);

    int markDone(@Param("id") Long id);

    int markFail(@Param("id") Long id);

    int deleteOlderThan(@Param("before") Date before, @Param("status") String status);
}

