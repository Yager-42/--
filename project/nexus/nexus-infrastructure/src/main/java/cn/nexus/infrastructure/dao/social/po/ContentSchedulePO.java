package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 定时发布任务表 PO。
 */
@Data
public class ContentSchedulePO {
    private Long taskId;
    private Long userId;
    /** 绑定的 postId（=draftId）。 */
    private Long postId;
    private String contentData;
    private Date scheduleTime;
    private Integer status;
    private Integer retryCount;
    private String idempotentToken;
    private Integer isCanceled;
    private String lastError;
    private Integer alarmSent;
}
