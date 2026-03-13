package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedCardBaseVO {
    private Long postId;
    private Long authorId;
    private String text;
    private String summary;
    private Integer mediaType;
    private String mediaInfo;
    private Long publishTime;
}
