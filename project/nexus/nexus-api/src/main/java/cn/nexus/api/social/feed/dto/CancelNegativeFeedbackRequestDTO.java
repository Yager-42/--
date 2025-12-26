package cn.nexus.api.social.feed.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 取消负反馈请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelNegativeFeedbackRequestDTO {
    private Long userId;
    private Long targetId;
}
