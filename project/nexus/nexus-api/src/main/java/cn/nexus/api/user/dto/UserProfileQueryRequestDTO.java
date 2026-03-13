package cn.nexus.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户 Profile 查询请求：用于查看他人 profile。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileQueryRequestDTO {
    private Long targetUserId;
}

