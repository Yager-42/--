package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 处罚 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPunishmentDTO {
    private Long punishId;
    private Long userId;
    private String type;
    /** ACTIVE/REVOKED/EXPIRED */
    private String status;
    private Long startTime;
    private Long endTime;
    private String reasonCode;
    private Long decisionId;
    private Long operatorId;
    private Long createTime;
    private Long updateTime;
}

