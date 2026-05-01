package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentDetailResponseDTO {
    private Long postId;
    private Long authorId;

    private String authorNickname;
    private String authorAvatarUrl;

    private String title;
    private String content;
    private String summary;
    private Integer summaryStatus;

    private Integer mediaType;
    private String mediaInfo;
    private String locationInfo;

    private Integer status;
    private Integer visibility;
    private Integer versionNum;
    private Boolean edited;
    private Long createTime;

    private Long likeCount;
    private Long favoriteCount;
    private Boolean liked;
    private Boolean faved;
}
