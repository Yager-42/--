package cn.nexus.domain.social.model.valobj;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索引擎返回结果：hits + 可选聚合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchEngineResultVO {

    /** ES took（毫秒）。 */
    private long tookMs;

    /** 总命中数（track_total_hits=true）。 */
    private long totalHits;

    private List<SearchHitVO> hits;

    /**
     * 聚合结果：key -> (bucketKey -> docCount)。
     *
     * <p>示例：mediaType/postTypes</p>
     */
    private Map<String, Map<String, Long>> aggs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchHitVO {
        /** 高亮内容（仅 POST；可空）。 */
        private String highlightContentText;
        /** 命中源文档。 */
        private SearchDocumentVO source;
    }
}

