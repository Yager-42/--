package cn.nexus.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 个人主页关系统计：关注/粉丝/好友与是否已关注。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRelationStatsDTO {
    private long followCount;
    private long followerCount;
    private long friendCount;
    private boolean isFollow;
}

