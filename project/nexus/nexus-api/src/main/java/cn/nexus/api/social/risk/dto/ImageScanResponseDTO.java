package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片扫描响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageScanResponseDTO {
    private String taskId;
}
