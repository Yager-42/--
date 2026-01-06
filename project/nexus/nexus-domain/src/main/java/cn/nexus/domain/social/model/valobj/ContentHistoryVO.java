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
    /**
     * 分页游标（下一次查询的偏移量），为空表示没有更多。
     */
    private Integer nextCursor;
    /**
     * 状态码（例如 NO_PERMISSION / REBUILD_FAILED），为空表示正常。
     */
    private String status;

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
