package cn.nexus.api.kv.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KvCommentContentDTO {
    private Long noteId;
    private String yearMonth;
    private String contentId;
    private String content;
}
