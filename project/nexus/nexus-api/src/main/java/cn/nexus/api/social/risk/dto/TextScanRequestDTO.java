package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文本扫描请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextScanRequestDTO {
    private String content;
    private Long userId;
    private String scenario;
}
