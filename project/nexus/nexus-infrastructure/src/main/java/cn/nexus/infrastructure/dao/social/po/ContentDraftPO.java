package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 草稿表 PO。
 */
@Data
public class ContentDraftPO {
    private Long draftId;
    private Long userId;
    private String draftContent;
    private String deviceId;
    private String clientVersion;
    private Date updateTime;
}
