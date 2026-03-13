package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ReliableMqOutboxPO;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IReliableMqOutboxDao {

    int insertIgnore(ReliableMqOutboxPO po);

    List<ReliableMqOutboxPO> selectReady(@Param("now") Date now, @Param("limit") int limit);

    int markSent(@Param("id") Long id);

    int markRetry(@Param("id") Long id,
                  @Param("retryCount") Integer retryCount,
                  @Param("nextRetryAt") Date nextRetryAt,
                  @Param("lastError") String lastError,
                  @Param("status") String status);
}
