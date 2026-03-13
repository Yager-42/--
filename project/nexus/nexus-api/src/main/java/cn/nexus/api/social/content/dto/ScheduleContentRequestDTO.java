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
    /** 绑定的 postId（=draftId）。 */
    private Long postId;
    private Long publishTime;
    private String timezone;
}
