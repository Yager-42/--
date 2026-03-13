package cn.nexus.api.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Login request.
 *
 * <p>Minimal dev login: userId or username.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthLoginRequestDTO {
    private Long userId;
    private String username;
    private String nickname;
    private String avatarUrl;
}
