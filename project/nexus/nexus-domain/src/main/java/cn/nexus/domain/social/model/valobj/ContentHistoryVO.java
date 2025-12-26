package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 内容版本列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentHistoryVO {
    private List<ContentVersionVO> versions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentVersionVO {
        private Long versionId;
        private String content;
        private Long time;
    }
}
