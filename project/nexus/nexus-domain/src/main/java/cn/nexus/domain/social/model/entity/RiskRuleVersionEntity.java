package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风控规则版本实体：用于发布/回滚与审计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleVersionEntity {
    private Long version;
    private String status;
    private String rulesJson;
    private Long createBy;
    private Long publishBy;
    private Long publishTime;
    private Long createTime;
    private Long updateTime;
}

