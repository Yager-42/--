package cn.nexus.api.social.community.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 修改成员角色请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupRoleRequestDTO {
    private Long groupId;
    private Long targetId;
    private Long roleId;
}
