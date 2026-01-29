package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风控动作（对外 DTO）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskActionDTO {
    private String type;
    /** 动作参数 JSON 字符串（可选） */
    private String params;
}

