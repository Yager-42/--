package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 通知事件收件箱表映射：interaction_notify_inbox。
 *
 * @author codex
 * @since 2026-01-21
 */
@Data
public class InteractionNotifyInboxPO {
    private String eventId;
    private String eventType;
    private String payload;
    private String status;
    private Date createTime;
    private Date updateTime;
}

