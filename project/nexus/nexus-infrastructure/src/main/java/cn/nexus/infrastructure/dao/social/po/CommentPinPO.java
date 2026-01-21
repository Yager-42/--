package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

/**
 * 评论置顶表映射，对应 interaction_comment_pin。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
public class CommentPinPO {
    private Long postId;
    private Long commentId;
    private Date createTime;
    private Date updateTime;
}

