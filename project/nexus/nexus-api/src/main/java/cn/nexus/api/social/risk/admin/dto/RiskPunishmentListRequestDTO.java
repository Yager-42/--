package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询处罚请求（GET 参数）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPunishmentListRequestDTO {
    private Long userId;
    private String type;
    /** 可选：开始时间（毫秒时间戳，按 create_time 过滤） */
    private Long beginTime;
    /** 可选：结束时间（毫秒时间戳，按 create_time 过滤） */
    private Long endTime;
    private Integer limit;
    private Integer offset;
}

