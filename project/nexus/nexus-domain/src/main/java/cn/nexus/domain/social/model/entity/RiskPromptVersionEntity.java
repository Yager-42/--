package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风控 Prompt 版本实体：用于 LLM 提示词灰度/回滚与审计追溯。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPromptVersionEntity {
    private Long version;
    /** TEXT/IMAGE */
    private String contentType;
    /** DRAFT/PUBLISHED/ROLLED_BACK */
    private String status;
    /** System Prompt 文本 */
    private String promptText;
    /** 可选：绑定模型名 */
    private String model;
    private Long createBy;
    private Long publishBy;
    private Long publishTime;
    private Long createTime;
    private Long updateTime;
}

