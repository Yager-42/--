package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IContentPublishAttemptRepository;
import cn.nexus.domain.social.model.entity.ContentPublishAttemptEntity;
import cn.nexus.domain.social.model.valobj.ContentPublishAttemptStatusEnumVO;
import cn.nexus.infrastructure.dao.social.IContentPublishAttemptDao;
import cn.nexus.infrastructure.dao.social.po.ContentPublishAttemptPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 内容发布尝试仓储 MyBatis 实现。
 *
 * @author {$authorName}
 * @since 2026-01-11
 */
@Repository
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
public class ContentPublishAttemptRepository implements IContentPublishAttemptRepository {

    private final IContentPublishAttemptDao contentPublishAttemptDao;

    /**
     * 创建一条发布尝试记录。
     *
     * @param attempt 发布尝试实体 {@link ContentPublishAttemptEntity}
     * @return 创建后的发布尝试实体 {@link ContentPublishAttemptEntity}
     */
    @Override
    public ContentPublishAttemptEntity create(ContentPublishAttemptEntity attempt) {
        contentPublishAttemptDao.insert(toPO(attempt));
        return attempt;
    }

    /**
     * 按幂等 Token 查询发布尝试。
     *
     * @param token 幂等 Token {@link String}
     * @return 发布尝试实体（不存在则返回 {@code null}） {@link ContentPublishAttemptEntity}
     */
    @Override
    @Transactional(readOnly = true)
    public ContentPublishAttemptEntity findByToken(String token) {
        return toEntity(contentPublishAttemptDao.selectByToken(token));
    }

    /**
     * 按发布尝试 ID 查询发布尝试。
     *
     * @param attemptId 发布尝试 ID {@link Long}
     * @return 发布尝试实体（不存在则返回 {@code null}） {@link ContentPublishAttemptEntity}
     */
    @Override
    @Transactional(readOnly = true)
    public ContentPublishAttemptEntity findByAttemptId(Long attemptId) {
        return toEntity(contentPublishAttemptDao.selectById(attemptId));
    }

    /**
     * 查询某条内容的最近一条“进行中的尝试”。
     *
     * @param postId 内容 ID {@link Long}
     * @param userId 用户 ID {@link Long}
     * @return 最近一条进行中的发布尝试（不存在则返回 {@code null}） {@link ContentPublishAttemptEntity}
     */
    @Override
    @Transactional(readOnly = true)
    public ContentPublishAttemptEntity findLatestActiveAttempt(Long postId, Long userId) {
        return toEntity(contentPublishAttemptDao.selectLatestActiveAttempt(
                postId,
                userId,
                ContentPublishAttemptStatusEnumVO.CREATED.getCode(),
                ContentPublishAttemptStatusEnumVO.TRANSCODING.getCode(),
                ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode()));
    }

    /**
     * 推进发布尝试状态。
     *
     * <p>该更新带有 {@code expectedAttemptStatus} 作为条件，用于避免并发/乱序回写导致的状态回退。</p>
     *
     * @param attemptId 发布尝试 ID {@link Long}
     * @param attemptStatus 新的尝试状态 {@link Integer}
     * @param riskStatus 新的风控状态 {@link Integer}
     * @param transcodeStatus 新的转码状态 {@link Integer}
     * @param transcodeJobId 转码任务 ID（可为空） {@link String}
     * @param publishedVersionNum 已发布版本号（可为空） {@link Integer}
     * @param errorCode 错误码（可为空） {@link String}
     * @param errorMessage 错误信息（可为空） {@link String}
     * @param expectedAttemptStatus 期望的当前尝试状态（CAS 条件） {@link Integer}
     * @return 是否更新成功 {@code boolean}
     */
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
        po.setSnapshotTitle(entity.getSnapshotTitle());
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
                .snapshotTitle(po.getSnapshotTitle())
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

