package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

/**
 * 评论持久化对象，对应 interaction_comment。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
public class CommentPO {
    private Long commentId;
    private Long postId;
    private Long userId;
    private Long rootId;
    private Long parentId;
    private Long replyToId;
    /**
     * Comment body UUID stored in KV (comment_content).
     */
    private String contentId;
    private Integer status;
    private Long likeCount;
    private Long replyCount;
    private Date createTime;
    private Date updateTime;
}

