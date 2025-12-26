package cn.nexus.api.social.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 热门搜索结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchTrendingResponseDTO {
    private List<String> keywords;
}
