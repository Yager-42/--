package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 内容历史结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentHistoryResponseDTO {
    private List<ContentVersionDTO> versions;
    /**
     * 下一次分页的偏移量，null 表示没有更多。
     */
    private Integer nextCursor;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentVersionDTO {
        private Long versionId;
        private String content;
        private Long time;
    }
}
