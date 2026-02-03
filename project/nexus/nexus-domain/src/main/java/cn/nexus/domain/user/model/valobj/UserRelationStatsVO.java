package cn.nexus.domain.user.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 个人主页关系统计（领域侧）：关注/粉丝/好友与是否已关注。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRelationStatsVO {
    private long followCount;
    private long followerCount;
    private long friendCount;
    private boolean isFollow;
}

