package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片扫描请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageScanRequestDTO {
    private String imageUrl;
    private Long userId;
}
