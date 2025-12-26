package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultVO {
    private List<SearchItemVO> items;
    private String facets;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchItemVO {
        private String id;
        private String type;
        private String title;
        private String summary;
    }
}
