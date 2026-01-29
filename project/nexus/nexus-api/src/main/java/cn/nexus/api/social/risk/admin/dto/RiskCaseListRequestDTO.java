package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人审工单查询请求（GET 参数）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskCaseListRequestDTO {
    private String status;
    private String queue;
    /** 可选：开始时间（毫秒时间戳） */
    private Long beginTime;
    /** 可选：结束时间（毫秒时间戳） */
    private Long endTime;
    private Integer limit;
    private Integer offset;
}

