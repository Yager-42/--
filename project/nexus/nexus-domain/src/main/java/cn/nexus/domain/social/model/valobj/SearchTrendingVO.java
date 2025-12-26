package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 热搜。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchTrendingVO {
    private List<String> keywords;
}
