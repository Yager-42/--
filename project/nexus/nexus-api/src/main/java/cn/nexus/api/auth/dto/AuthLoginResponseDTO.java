package cn.nexus.api.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Login response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthLoginResponseDTO {
    private Long userId;
    private String tokenName;
    private String tokenPrefix;
    private String token;
}
