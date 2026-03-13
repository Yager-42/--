package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容发布尝试实体：承载一次发布请求的过程审计与状态推进。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentPublishAttemptEntity {
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

    private Long createTime;
    private Long updateTime;
}

