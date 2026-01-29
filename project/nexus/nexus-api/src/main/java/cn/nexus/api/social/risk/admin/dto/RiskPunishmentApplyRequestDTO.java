package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 施加处罚请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPunishmentApplyRequestDTO {
    private Long userId;
    private String type;
    /** 可选：关联决策ID（用于幂等与追溯） */
    private Long decisionId;
    /** 原因码（可选） */
    private String reasonCode;
    /** 可选：生效开始时间（毫秒）；为空表示立即生效 */
    private Long startTime;
    /** 可选：生效结束时间（毫秒）；与 durationSeconds 二选一 */
    private Long endTime;
    /** 可选：持续秒数（与 endTime 二选一） */
    private Long durationSeconds;
}

