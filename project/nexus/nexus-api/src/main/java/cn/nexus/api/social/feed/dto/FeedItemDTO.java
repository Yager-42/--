package cn.nexus.api.social.feed.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Feed 条目。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedItemDTO {
    private Long postId;
    private Long authorId;
    private String authorNickname;
    private String authorAvatar;
    private String text;
    private String summary;
    private Integer mediaType;
    private String mediaInfo;
    private Long publishTime;
    private String source;
    private Long likeCount;
    private Boolean liked;
    private Boolean followed;
    private Boolean seen;
}
