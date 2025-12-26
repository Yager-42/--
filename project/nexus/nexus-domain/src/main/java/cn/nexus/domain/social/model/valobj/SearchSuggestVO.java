package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索联想。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchSuggestVO {
    private List<String> suggestions;
}
