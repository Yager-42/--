package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RiskDecisionLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IRiskDecisionLogDao {

    int insert(RiskDecisionLogPO po);

    RiskDecisionLogPO selectByDecisionId(@Param("decisionId") Long decisionId);

    RiskDecisionLogPO selectByUserEvent(@Param("userId") Long userId, @Param("eventId") String eventId);

    int updateResult(@Param("decisionId") Long decisionId,
                     @Param("result") String result,
                     @Param("reasonCode") String reasonCode,
                     @Param("signalsJson") String signalsJson,
                     @Param("actionsJson") String actionsJson,
                     @Param("extJson") String extJson);

    List<RiskDecisionLogPO> selectByUser(@Param("userId") Long userId,
                                        @Param("limit") Integer limit,
                                        @Param("offset") Integer offset);

    List<RiskDecisionLogPO> selectByFilter(@Param("userId") Long userId,
                                          @Param("actionType") String actionType,
                                          @Param("scenario") String scenario,
                                          @Param("result") String result,
                                          @Param("beginTime") java.util.Date beginTime,
                                          @Param("endTime") java.util.Date endTime,
                                          @Param("limit") Integer limit,
                                          @Param("offset") Integer offset);
}
