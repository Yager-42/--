package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询决策日志请求（GET 参数）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecisionLogListRequestDTO {
    private Long userId;
    private String actionType;
    private String scenario;
    private String result;
    /** 可选：开始时间（毫秒时间戳，按 create_time 过滤） */
    private Long beginTime;
    /** 可选：结束时间（毫秒时间戳，按 create_time 过滤） */
    private Long endTime;
    private Integer limit;
    private Integer offset;
}

