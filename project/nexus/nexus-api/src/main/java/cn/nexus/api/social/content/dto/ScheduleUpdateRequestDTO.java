package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 变更定时发布请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleUpdateRequestDTO {
    private Long taskId;
    private Long userId;
    private Long publishTime;
    private String contentData;
    private String reason;
}
