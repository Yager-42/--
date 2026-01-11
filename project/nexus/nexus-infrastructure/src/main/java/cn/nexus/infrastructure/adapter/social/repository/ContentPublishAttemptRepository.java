package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IContentPublishAttemptRepository;
import cn.nexus.domain.social.model.entity.ContentPublishAttemptEntity;
import cn.nexus.infrastructure.dao.social.IContentPublishAttemptDao;
import cn.nexus.infrastructure.dao.social.po.ContentPublishAttemptPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 内容发布尝试仓储 MyBatis 实现。
 */
@Repository
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
public class ContentPublishAttemptRepository implements IContentPublishAttemptRepository {

    private final IContentPublishAttemptDao contentPublishAttemptDao;

    @Override
    public ContentPublishAttemptEntity create(ContentPublishAttemptEntity attempt) {
        contentPublishAttemptDao.insert(toPO(attempt));
        return attempt;
    }

    @Override
    @Transactional(readOnly = true)
    public ContentPublishAttemptEntity findByToken(String token) {
        return toEntity(contentPublishAttemptDao.selectByToken(token));
    }

    @Override
    @Transactional(readOnly = true)
    public ContentPublishAttemptEntity findByAttemptId(Long attemptId) {
        return toEntity(contentPublishAttemptDao.selectById(attemptId));
    }

    @Override
    public boolean updateAttemptStatus(Long attemptId,
                                       Integer attemptStatus,
                                       Integer riskStatus,
                                       Integer transcodeStatus,
                                       String transcodeJobId,
                                       Integer publishedVersionNum,
                                       String errorCode,
                                       String errorMessage,
                                       Integer expectedAttemptStatus) {
        return contentPublishAttemptDao.updateStatus(
                attemptId,
                attemptStatus,
                riskStatus,
                transcodeStatus,
                transcodeJobId,
                publishedVersionNum,
                errorCode,
                errorMessage,
                expectedAttemptStatus) > 0;
    }

    private ContentPublishAttemptPO toPO(ContentPublishAttemptEntity entity) {
        ContentPublishAttemptPO po = new ContentPublishAttemptPO();
        po.setAttemptId(entity.getAttemptId());
        po.setPostId(entity.getPostId());
        po.setUserId(entity.getUserId());
        po.setIdempotentToken(entity.getIdempotentToken());
        po.setTranscodeJobId(entity.getTranscodeJobId());
        po.setAttemptStatus(entity.getAttemptStatus());
        po.setRiskStatus(entity.getRiskStatus());
        po.setTranscodeStatus(entity.getTranscodeStatus());
        po.setSnapshotContent(entity.getSnapshotContent());
        po.setSnapshotMedia(entity.getSnapshotMedia());
        po.setLocationInfo(entity.getLocationInfo());
        po.setVisibility(entity.getVisibility());
        po.setPublishedVersionNum(entity.getPublishedVersionNum());
        po.setErrorCode(entity.getErrorCode());
        po.setErrorMessage(entity.getErrorMessage());
        po.setCreateTime(entity.getCreateTime() == null ? null : new Date(entity.getCreateTime()));
        po.setUpdateTime(entity.getUpdateTime() == null ? null : new Date(entity.getUpdateTime()));
        return po;
    }

    private ContentPublishAttemptEntity toEntity(ContentPublishAttemptPO po) {
        if (po == null) {
            return null;
        }
        return ContentPublishAttemptEntity.builder()
                .attemptId(po.getAttemptId())
                .postId(po.getPostId())
                .userId(po.getUserId())
                .idempotentToken(po.getIdempotentToken())
                .transcodeJobId(po.getTranscodeJobId())
                .attemptStatus(po.getAttemptStatus())
                .riskStatus(po.getRiskStatus())
                .transcodeStatus(po.getTranscodeStatus())
                .snapshotContent(po.getSnapshotContent())
                .snapshotMedia(po.getSnapshotMedia())
                .locationInfo(po.getLocationInfo())
                .visibility(po.getVisibility())
                .publishedVersionNum(po.getPublishedVersionNum())
                .errorCode(po.getErrorCode())
                .errorMessage(po.getErrorMessage())
                .createTime(po.getCreateTime() == null ? null : po.getCreateTime().getTime())
                .updateTime(po.getUpdateTime() == null ? null : po.getUpdateTime().getTime())
                .build();
    }
}

