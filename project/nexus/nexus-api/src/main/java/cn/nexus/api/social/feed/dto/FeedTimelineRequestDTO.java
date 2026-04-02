package cn.nexus.api.social.feed.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 主页 Feed 请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedTimelineRequestDTO {
    private Long userId;
    private String cursor;
    private Integer limit;
    private String feedType;
    private String direction;
    private Long cursorTs;
    private Long cursorPostId;
}
