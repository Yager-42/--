package cn.nexus.trigger.http.social.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 取消定时发布请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleCancelRequestDTO {
    private Long taskId;
    private Long userId;
    private String reason;
}
