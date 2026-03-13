package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回滚规则版本请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleVersionRollbackRequestDTO {
    /** 可选：指定回滚到哪个版本；为空表示回滚到上一个已发布版本 */
    private Long toVersion;
}

