package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RiskPunishmentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface IRiskPunishmentDao {

    int insert(RiskPunishmentPO po);

    int insertIgnore(RiskPunishmentPO po);

    RiskPunishmentPO selectByDecisionAndType(@Param("decisionId") Long decisionId, @Param("type") String type);

    List<RiskPunishmentPO> selectActiveByUser(@Param("userId") Long userId, @Param("now") Date now);

    int revoke(@Param("punishId") Long punishId, @Param("operatorId") Long operatorId, @Param("now") Date now);

    List<RiskPunishmentPO> selectByUser(@Param("userId") Long userId,
                                       @Param("limit") Integer limit,
                                       @Param("offset") Integer offset);

    List<RiskPunishmentPO> selectByFilter(@Param("userId") Long userId,
                                         @Param("type") String type,
                                         @Param("beginTime") Date beginTime,
                                         @Param("endTime") Date endTime,
                                         @Param("limit") Integer limit,
                                         @Param("offset") Integer offset);
}
