package cn.nexus.api.social.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索联想结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchSuggestResponseDTO {
    private List<String> suggestions;
}
