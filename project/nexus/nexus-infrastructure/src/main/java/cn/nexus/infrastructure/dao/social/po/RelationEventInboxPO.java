package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 关系事件收件箱表映射。
 */
@Data
public class RelationEventInboxPO {
    private Long id;
    private String eventType;
    private String fingerprint;
    private String payload;
    private String status;
    private Date createTime;
    private Date updateTime;
}
