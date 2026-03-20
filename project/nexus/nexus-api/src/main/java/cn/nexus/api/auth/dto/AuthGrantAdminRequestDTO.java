package cn.nexus.api.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员赋权请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthGrantAdminRequestDTO {
    private Long userId;
}
