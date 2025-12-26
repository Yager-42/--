package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 定时发布结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleContentResponseDTO {
    private Long taskId;
    private String status;
}
