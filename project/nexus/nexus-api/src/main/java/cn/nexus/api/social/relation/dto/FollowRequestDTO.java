package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关注请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowRequestDTO {
    private Long sourceId;
    private Long targetId;
}
