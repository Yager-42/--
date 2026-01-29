package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RiskFeedbackPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IRiskFeedbackDao {

    int insert(RiskFeedbackPO po);

    RiskFeedbackPO selectById(@Param("feedbackId") Long feedbackId);

    int updateStatus(@Param("feedbackId") Long feedbackId,
                     @Param("status") String status,
                     @Param("result") String result,
                     @Param("operatorId") Long operatorId);

    List<RiskFeedbackPO> selectByUser(@Param("userId") Long userId,
                                     @Param("limit") Integer limit,
                                     @Param("offset") Integer offset);
}

