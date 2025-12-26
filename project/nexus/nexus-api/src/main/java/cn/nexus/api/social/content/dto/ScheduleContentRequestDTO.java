package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 定时发布请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleContentRequestDTO {
    private String contentData;
    private Long publishTime;
    private String timezone;
}
