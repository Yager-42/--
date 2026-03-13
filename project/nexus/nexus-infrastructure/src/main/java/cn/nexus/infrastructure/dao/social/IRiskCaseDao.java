package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RiskCasePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IRiskCaseDao {

    int insertIgnore(RiskCasePO po);

    RiskCasePO selectById(@Param("caseId") Long caseId);

    RiskCasePO selectByDecisionId(@Param("decisionId") Long decisionId);

    int updateAssign(@Param("caseId") Long caseId,
                     @Param("assignee") Long assignee,
                     @Param("expectedStatus") String expectedStatus);

    int updateFinish(@Param("caseId") Long caseId,
                     @Param("result") String result,
                     @Param("evidenceJson") String evidenceJson,
                     @Param("expectedStatus") String expectedStatus);

    List<RiskCasePO> selectList(@Param("status") String status,
                               @Param("queue") String queue,
                               @Param("beginTime") java.util.Date beginTime,
                               @Param("endTime") java.util.Date endTime,
                               @Param("limit") Integer limit,
                               @Param("offset") Integer offset);
}
