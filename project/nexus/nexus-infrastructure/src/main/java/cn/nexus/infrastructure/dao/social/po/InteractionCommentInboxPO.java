package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

/**
 * 评论事件收件箱表映射：interaction_comment_inbox。
 *
 * @author codex
 * @since 2026-01-22
 */
@Data
public class InteractionCommentInboxPO {
    private String eventId;
    private String eventType;
    private String payload;
    private Date createTime;
}

