package cn.nexus.api.social.feed.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 个人页 Feed 请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileFeedRequestDTO {
    private Long targetId;
    private Long visitorId;
    private String cursor;
    private Integer limit;
}
