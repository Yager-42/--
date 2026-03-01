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
    /** 草稿关联的媒体标识列表（逗号/JSON） */
    private String mediaIds;
    private String deviceId;
    private Long clientVersion;
    private Date updateTime;
}
