package cn.nexus.api.social.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 热门搜索请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchTrendingRequestDTO {
    private String category;
}
