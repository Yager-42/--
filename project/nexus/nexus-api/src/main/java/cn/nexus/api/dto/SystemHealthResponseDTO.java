package cn.nexus.api.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 系统健康检查响应数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemHealthResponseDTO {
    /** 当前服务状态描述 */
    private String status;
}
