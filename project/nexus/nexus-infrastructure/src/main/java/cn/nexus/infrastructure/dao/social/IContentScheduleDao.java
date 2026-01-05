package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentSchedulePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IContentScheduleDao {

    int insert(ContentSchedulePO po);

    int updateStatus(@Param("taskId") Long taskId, @Param("status") Integer status, @Param("retryCount") Integer retryCount);

    java.util.List<ContentSchedulePO> selectPending(@Param("before") java.util.Date before, @Param("limit") Integer limit);
}
