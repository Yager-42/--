package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评论结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentResultVO {
    private Long commentId;
    private Long createTime;
}
