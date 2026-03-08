package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 内容发布尝试表 PO。
 */
@Data
public class ContentPublishAttemptPO {
    private Long attemptId;
    private Long postId;
    private Long userId;
    private String idempotentToken;
    private String transcodeJobId;

    private Integer attemptStatus;
    private Integer riskStatus;
    private Integer transcodeStatus;

    private String snapshotTitle;
    private String snapshotContent;
    private String snapshotMedia;
    private String locationInfo;
    private Integer visibility;

    private Integer publishedVersionNum;
    private String errorCode;
    private String errorMessage;

    private Date createTime;
    private Date updateTime;
}

