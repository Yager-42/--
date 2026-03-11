package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ReliableMqConsumerRecordPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IReliableMqConsumerRecordDao {

    int insertIgnore(ReliableMqConsumerRecordPO po);

    ReliableMqConsumerRecordPO selectOne(@Param("eventId") String eventId,
                                         @Param("consumerName") String consumerName);

    int updateStatus(@Param("eventId") String eventId,
                     @Param("consumerName") String consumerName,
                     @Param("status") String status,
                     @Param("lastError") String lastError);
}
