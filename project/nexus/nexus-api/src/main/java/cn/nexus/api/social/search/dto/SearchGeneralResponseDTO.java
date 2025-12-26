package cn.nexus.api.social.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 综合搜索结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchGeneralResponseDTO {
    private List<SearchItemDTO> items;
    private String facets;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchItemDTO {
        private String id;
        private String type;
        private String title;
        private String summary;
    }
}
