package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 内容历史表 PO。
 */
@Data
public class ContentHistoryPO {
    private Long historyId;
    private Long postId;
    private Integer versionNum;
    private String snapshotContent;
    private String snapshotMedia;
    private Date createTime;
}
