package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Feed 条目。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedItemVO {
    private Long postId;
    private Long authorId;
    private String text;
    private Long publishTime;
    private String source;
}
