package cn.nexus.domain.user.model.valobj;

import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 个人主页聚合视图（领域侧）：Profile + 关系统计 + 风控能力。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfilePageVO {
    private UserProfileVO profile;
    private String status;
    private UserRelationStatsVO relation;
    private UserRiskStatusVO risk;
}

