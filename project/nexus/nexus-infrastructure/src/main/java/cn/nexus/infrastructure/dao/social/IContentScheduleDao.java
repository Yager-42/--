package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentSchedulePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IContentScheduleDao {

    int insert(ContentSchedulePO po);

    int updateStatus(@Param("taskId") Long taskId,
                     @Param("status") Integer status,
                     @Param("retryCount") Integer retryCount,
                     @Param("lastError") String lastError,
                     @Param("alarmSent") Integer alarmSent,
                     @Param("nextTime") java.util.Date nextTime,
                     @Param("expectedStatus") Integer expectedStatus);

    java.util.List<ContentSchedulePO> selectPending(@Param("before") java.util.Date before, @Param("limit") Integer limit);

    ContentSchedulePO selectById(@Param("taskId") Long taskId);

    ContentSchedulePO selectByToken(@Param("idempotentToken") String token);

    int cancel(@Param("taskId") Long taskId, @Param("userId") Long userId, @Param("reason") String reason);

    int updateSchedule(@Param("taskId") Long taskId,
                       @Param("userId") Long userId,
                       @Param("scheduleTime") java.util.Date scheduleTime,
                       @Param("contentData") String contentData,
                       @Param("idempotentToken") String idempotentToken,
                       @Param("reason") String reason);
}
