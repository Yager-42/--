package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回滚 Prompt 版本请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPromptVersionRollbackRequestDTO {
    /** 可选：指定回滚到的版本；为空表示回滚到最近的 ROLLED_BACK 版本 */
    private Long toVersion;
}

