package cn.nexus.domain.social.model.valobj;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索引擎查询参数（已归一化）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchEngineQueryVO {

    /** 已归一化后的关键词（非空）。 */
    private String keyword;

    /** 排序：RELEVANT/LATEST。 */
    private String sort;

    private int offset;
    private int limit;

    /** 是否返回 facets（若 false：不执行 ES aggregations）。 */
    private boolean includeFacets;

    /** 可选：仅对 POST 生效。 */
    private Integer mediaType;

    /** 可选：仅对 POST 生效。 */
    private List<String> postTypes;

    /** 可选：仅对 POST 生效；毫秒时间戳。 */
    private Long timeFromMs;
    private Long timeToMs;
}

