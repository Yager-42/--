package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发布尝试查询结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishAttemptResponseDTO {
    private Long attemptId;
    private Long postId;
    private Long userId;
    private String idempotentToken;
    private String transcodeJobId;

    private Integer attemptStatus;
    private Integer riskStatus;
    private Integer transcodeStatus;

    private Integer publishedVersionNum;
    private String errorCode;
    private String errorMessage;

    private Long createTime;
    private Long updateTime;
}

