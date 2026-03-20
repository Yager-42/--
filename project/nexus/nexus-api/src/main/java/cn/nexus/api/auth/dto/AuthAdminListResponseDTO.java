package cn.nexus.api.auth.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员列表响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthAdminListResponseDTO {
    private List<Long> userIds;
    private List<AuthAdminDTO> admins;
}
