package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工单审核结论请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskCaseDecisionRequestDTO {
    /** PASS/BLOCK */
    private String result;
    /** 原因码（可选） */
    private String reasonCode;
    /** 证据/备注（可选，JSON 字符串） */
    private String evidenceJson;
    /** 期望状态（并发保护）；默认 ASSIGNED */
    private String expectedStatus;

    /** 可选处罚建议：处罚类型（POST_BAN/COMMENT_BAN/LOGIN_BAN/DM_BAN 等） */
    private String punishType;
    /** 可选处罚建议：处罚持续秒数（例如 3600=1h） */
    private Long punishDurationSeconds;
}

