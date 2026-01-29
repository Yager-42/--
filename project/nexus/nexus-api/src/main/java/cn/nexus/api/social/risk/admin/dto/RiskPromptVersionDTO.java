package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prompt 版本 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPromptVersionDTO {
    private Long version;
    private String contentType;
    private String status;
    private String model;
    private Long createBy;
    private Long publishBy;
    private Long publishTime;
    private Long createTime;
    private Long updateTime;
    private String promptText;
}

