package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.InteractionReactionEventLogPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IInteractionReactionEventLogDao {

    int insertIgnore(InteractionReactionEventLogPO po);

    List<InteractionReactionEventLogPO> selectPage(@Param("targetType") String targetType,
                                                   @Param("reactionType") String reactionType,
                                                   @Param("cursor") Long cursor,
                                                   @Param("limit") int limit);
}
