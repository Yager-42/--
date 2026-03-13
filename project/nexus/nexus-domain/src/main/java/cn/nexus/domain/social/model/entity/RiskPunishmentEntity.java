package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风控处罚实体：以 MySQL 事实表为准，Redis 仅作为缓存/加速。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPunishmentEntity {
    private Long punishId;
    private Long userId;
    private String type;
    private String status;
    private Long startTime;
    private Long endTime;
    private String reasonCode;
    private Long decisionId;
    private Long operatorId;
    private Long createTime;
    private Long updateTime;
}

