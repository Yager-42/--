package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ReliableMqReplayRecordPO;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IReliableMqReplayRecordDao {

    int insertIgnore(ReliableMqReplayRecordPO po);

    List<ReliableMqReplayRecordPO> selectReady(@Param("now") Date now, @Param("limit") int limit);

    int markDone(@Param("id") Long id);

    int markRetry(@Param("id") Long id,
                  @Param("attempt") Integer attempt,
                  @Param("nextRetryAt") Date nextRetryAt,
                  @Param("lastError") String lastError,
                  @Param("status") String status);
}
