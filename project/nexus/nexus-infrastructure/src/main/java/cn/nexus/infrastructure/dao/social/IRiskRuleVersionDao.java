package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RiskRuleVersionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface IRiskRuleVersionDao {

    int insert(RiskRuleVersionPO po);

    RiskRuleVersionPO selectByVersion(@Param("version") Long version);

    RiskRuleVersionPO selectActive();

    List<RiskRuleVersionPO> selectAll();

    Long selectMaxVersion();

    int updateRulesJson(@Param("version") Long version,
                        @Param("rulesJson") String rulesJson,
                        @Param("expectedStatus") String expectedStatus);

    int markAllPublishedRolledBack(@Param("toStatus") String toStatus);

    int publish(@Param("version") Long version,
                @Param("status") String status,
                @Param("publishBy") Long publishBy,
                @Param("publishTime") Date publishTime);
}
