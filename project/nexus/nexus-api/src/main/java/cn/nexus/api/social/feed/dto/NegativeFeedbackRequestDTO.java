package cn.nexus.api.social.feed.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 负反馈请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NegativeFeedbackRequestDTO {
    private Long userId;
    private Long targetId;
    private String type;
    private String reasonCode;
    private List<String> extraTags;
}
