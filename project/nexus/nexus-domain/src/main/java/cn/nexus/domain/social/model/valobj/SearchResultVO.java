package cn.nexus.domain.social.model.valobj;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultVO {
    private List<SearchItemVO> items;
    private String nextAfter;
    private boolean hasMore;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchItemVO {
        private String id;
        private String authorId;
        private String title;
        private String description;
        private String coverImage;
        private List<String> tags;
        private String authorAvatar;
        private String authorNickname;
        private String tagJson;
        private Boolean liked;
        private Boolean faved;
        private Boolean isTop;
    }
}
