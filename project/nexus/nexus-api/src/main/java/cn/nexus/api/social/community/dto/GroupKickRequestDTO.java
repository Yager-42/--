package cn.nexus.api.social.community.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 踢人/封禁请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupKickRequestDTO {
    private Long groupId;
    private Long targetId;
    private String reason;
    private Boolean ban;
}
