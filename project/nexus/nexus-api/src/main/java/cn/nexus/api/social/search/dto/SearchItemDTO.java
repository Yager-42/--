package cn.nexus.api.social.search.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchItemDTO {
    private String id;
    private String title;
    private String description;
    private String coverImage;
    private List<String> tags;
    private String authorAvatar;
    private String authorNickname;
    private String tagJson;
    private Long likeCount;
    private Long favoriteCount;
    private Boolean liked;
    private Boolean faved;
    private Boolean isTop;
}
