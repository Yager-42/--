package cn.nexus.api.social.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索联想请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchSuggestRequestDTO {
    private String keyword;
}
