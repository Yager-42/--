package cn.nexus.domain.social.model.valobj.kv;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentContentItemVO {
    private Long postId;
    private String yearMonth;
    private String contentId;
    private String content;
}
