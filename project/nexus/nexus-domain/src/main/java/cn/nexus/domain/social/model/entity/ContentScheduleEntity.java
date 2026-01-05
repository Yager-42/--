package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 定时发布任务实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentScheduleEntity {
    private Long taskId;
    private Long userId;
    private String contentData;
    private Long scheduleTime;
    private Integer status;
    private Integer retryCount;
}
