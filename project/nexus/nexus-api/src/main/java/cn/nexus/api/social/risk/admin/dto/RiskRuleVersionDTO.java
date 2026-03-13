package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规则版本 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleVersionDTO {
    private Long version;
    /** DRAFT/PUBLISHED/ROLLED_BACK */
    private String status;
    private Long createBy;
    private Long publishBy;
    private Long publishTime;
    private Long createTime;
    private Long updateTime;
    /** 规则配置 JSON（大字段；必要时对外裁剪） */
    private String rulesJson;
}

