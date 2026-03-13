package cn.nexus.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户 Profile 返回：最小名片（username/nickname/avatarUrl）+ status。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponseDTO {
    private Long userId;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String status;
}

