package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.InteractionNotifyInboxPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IInteractionNotifyInboxDao {

    int insertIgnore(InteractionNotifyInboxPO po);

    int updateStatus(@Param("eventId") String eventId, @Param("status") String status);
}

