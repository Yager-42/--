package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 定时任务审计查询响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleAuditResponseDTO {
    private Long taskId;
    private Long userId;
    private Long scheduleTime;
    private Integer status;
    private Integer retryCount;
    private Integer isCanceled;
    private String lastError;
    private Integer alarmSent;
    private String contentData;
}
