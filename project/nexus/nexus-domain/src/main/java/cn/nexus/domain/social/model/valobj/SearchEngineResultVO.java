package cn.nexus.domain.social.model.valobj;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchEngineResultVO {
    private List<SearchHitVO> hits;
    private String nextAfter;
    private boolean hasMore;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchHitVO {
        private String highlightTitle;
        private String highlightBody;
        private SearchDocumentVO source;
    }
}
