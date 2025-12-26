package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文本扫描结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextScanResponseDTO {
    private String result;
    private List<String> tags;
}
