package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.Class2UserCounterRepairTaskPO;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IClass2UserCounterRepairTaskDao {

    int insertIgnore(@Param("taskId") Long taskId,
                     @Param("repairType") String repairType,
                     @Param("userId") Long userId,
                     @Param("dedupeKey") String dedupeKey,
                     @Param("status") String status,
                     @Param("retryCount") Integer retryCount,
                     @Param("nextRetryTime") Date nextRetryTime,
                     @Param("reason") String reason,
                     @Param("createTime") Date createTime,
                     @Param("updateTime") Date updateTime);

    List<Long> selectClaimableTaskIds(@Param("now") Date now, @Param("limit") int limit);

    int claimBatch(@Param("taskIds") List<Long> taskIds,
                   @Param("owner") String owner,
                   @Param("now") Date now,
                   @Param("leaseUntil") Date leaseUntil);

    List<Class2UserCounterRepairTaskPO> selectByTaskIds(@Param("taskIds") List<Long> taskIds);

    int markDone(@Param("taskId") Long taskId, @Param("owner") String owner, @Param("updateTime") Date updateTime);

    int markRetry(@Param("taskId") Long taskId,
                  @Param("owner") String owner,
                  @Param("nextRetryTime") Date nextRetryTime,
                  @Param("lastError") String lastError,
                  @Param("updateTime") Date updateTime);

    int release(@Param("taskId") Long taskId,
                @Param("owner") String owner,
                @Param("nextRetryTime") Date nextRetryTime,
                @Param("reason") String reason,
                @Param("updateTime") Date updateTime);
}

