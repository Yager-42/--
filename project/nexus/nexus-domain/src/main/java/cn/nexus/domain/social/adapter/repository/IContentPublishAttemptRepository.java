package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.ContentPublishAttemptEntity;

/**
 * 内容发布尝试仓储接口。
 */
public interface IContentPublishAttemptRepository {

    ContentPublishAttemptEntity create(ContentPublishAttemptEntity attempt);

    ContentPublishAttemptEntity findByToken(String token);

    ContentPublishAttemptEntity findByAttemptId(Long attemptId);

    /**
     * 查询指定 postId + userId 的“进行中 attempt”（CREATED/TRANSCODING/PENDING_REVIEW）里最新的一条。
     */
    ContentPublishAttemptEntity findLatestActiveAttempt(Long postId, Long userId);

    /**
     * 推进 Attempt 状态机（建议通过 expectedAttemptStatus 做并发幂等控制）。
     */
    boolean updateAttemptStatus(Long attemptId,
                                Integer attemptStatus,
                                Integer riskStatus,
                                Integer transcodeStatus,
                                String transcodeJobId,
                                Integer publishedVersionNum,
                                String errorCode,
                                String errorMessage,
                                Integer expectedAttemptStatus);
}
