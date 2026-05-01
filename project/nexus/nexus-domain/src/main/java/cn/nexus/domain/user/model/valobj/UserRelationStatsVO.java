package cn.nexus.domain.user.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户关系统计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRelationStatsVO {
    private long followings;
    private long followers;
    private long posts;
    private long likesReceived;
    private long favsReceived;
    private boolean isFollow;
}
