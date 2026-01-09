package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentDraftEntity;
import cn.nexus.domain.social.model.entity.ContentHistoryEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.infrastructure.dao.social.IContentDraftDao;
import cn.nexus.infrastructure.dao.social.IContentHistoryDao;
import cn.nexus.infrastructure.dao.social.IContentPostDao;
import cn.nexus.infrastructure.dao.social.IContentScheduleDao;
import cn.nexus.infrastructure.dao.social.po.ContentDraftPO;
import cn.nexus.infrastructure.dao.social.po.ContentHistoryPO;
import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.infrastructure.dao.social.po.ContentSchedulePO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 内容/媒体仓储 MyBatis 实现。
 */
@Repository
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
public class ContentRepository implements IContentRepository {

    private final IContentDraftDao contentDraftDao;
    private final IContentPostDao contentPostDao;
    private final IContentHistoryDao contentHistoryDao;
    private final IContentScheduleDao contentScheduleDao;

    @Override
    public ContentDraftEntity saveDraft(ContentDraftEntity draft) {
        contentDraftDao.insertOrUpdate(toDraftPO(draft));
        return draft;
    }

    @Override
    @Transactional(readOnly = true)
    public ContentDraftEntity findDraft(Long draftId) {
        ContentDraftPO po = contentDraftDao.selectById(draftId);
        return toDraftEntity(po);
    }

    @Transactional
    @Override
    public ContentPostEntity savePost(ContentPostEntity post) {
        contentPostDao.insert(toPostPO(post));
        return post;
    }

    @Override
    @Transactional(readOnly = true)
    public ContentPostEntity findPost(Long postId) {
        return toPostEntity(contentPostDao.selectById(postId));
    }

    @Override
    @Transactional(readOnly = false)
    public ContentPostEntity findPostForUpdate(Long postId) {
        return toPostEntity(contentPostDao.selectByIdForUpdate(postId));
    }

    @Override
    public boolean updatePostStatusAndContent(Long postId, Integer status, Integer versionNum, Boolean edited,
                                              String contentText, String mediaInfo, String locationInfo, Integer visibility) {
        Integer expectedVersion = versionNum == null ? null : Math.max(0, versionNum - 1);
        int rows = contentPostDao.updateContentAndVersion(
                postId,
                contentText,
                mediaInfo,
                locationInfo,
                versionNum,
                edited == null ? null : (edited ? 1 : 0),
                status,
                visibility,
                expectedVersion);
        return rows > 0;
    }

    @Override
    public void saveHistory(ContentHistoryEntity history) {
        contentHistoryDao.insert(toHistoryPO(history));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContentHistoryEntity> listHistory(Long postId, Integer limit, Integer offset) {
        return contentHistoryDao.selectByPostId(postId, limit, offset).stream()
                .map(this::toHistoryEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ContentHistoryEntity findHistoryVersion(Long postId, Integer versionNum) {
        return toHistoryEntity(contentHistoryDao.selectOne(postId, versionNum));
    }

    @Override
    public boolean softDelete(Long postId, Long userId) {
        if (userId == null) {
            return false;
        }
        return contentPostDao.updateStatusWithUser(postId, userId, 6) > 0;
    }

    @Override
    public ContentScheduleEntity createSchedule(ContentScheduleEntity schedule) {
        contentScheduleDao.insert(toSchedulePO(schedule));
        return schedule;
    }

    @Override
    public boolean updateScheduleStatus(Long taskId, Integer status, Integer retryCount, String lastError, Integer alarmSent, Long nextScheduleTime, Integer expectedStatus) {
        return contentScheduleDao.updateStatus(taskId, status, retryCount, lastError, alarmSent,
                nextScheduleTime == null ? null : new Date(nextScheduleTime), expectedStatus) > 0;
    }

    @Override
    public List<ContentScheduleEntity> listPendingSchedules(Long beforeTime, Integer limit) {
        List<ContentSchedulePO> list = contentScheduleDao.selectPending(beforeTime == null ? null : new Date(beforeTime), limit);
        if (list == null) {
            return java.util.List.of();
        }
        return list.stream().map(this::toScheduleEntity).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ContentScheduleEntity findSchedule(Long taskId) {
        return toScheduleEntity(contentScheduleDao.selectById(taskId));
    }

    @Override
    public boolean cancelSchedule(Long taskId, Long userId, String reason) {
        ContentSchedulePO po = contentScheduleDao.selectById(taskId);
        if (po == null) {
            return false;
        }
        if (po.getStatus() != null && po.getStatus() != 0) {
            return false;
        }
        return contentScheduleDao.cancel(taskId, userId, reason) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public ContentScheduleEntity findScheduleByToken(String token) {
        return toScheduleEntity(contentScheduleDao.selectByToken(token));
    }

    @Override
    public boolean updateSchedule(Long taskId, Long userId, Long scheduleTime, String contentData, String idempotentToken, String reason) {
        return contentScheduleDao.updateSchedule(taskId, userId, scheduleTime == null ? null : new Date(scheduleTime), contentData, idempotentToken, reason) > 0;
    }


    private ContentDraftPO toDraftPO(ContentDraftEntity entity) {
        ContentDraftPO po = new ContentDraftPO();
        po.setDraftId(entity.getDraftId());
        po.setUserId(entity.getUserId());
        po.setDraftContent(entity.getDraftContent());
        po.setMediaIds(entity.getMediaIds());
        po.setDeviceId(entity.getDeviceId());
        po.setClientVersion(entity.getClientVersion());
        po.setUpdateTime(entity.getUpdateTime() == null ? null : new Date(entity.getUpdateTime()));
        return po;
    }

    private ContentDraftEntity toDraftEntity(ContentDraftPO po) {
        if (po == null) {
            return null;
        }
        return ContentDraftEntity.builder()
                .draftId(po.getDraftId())
                .userId(po.getUserId())
                .draftContent(po.getDraftContent())
                .mediaIds(po.getMediaIds())
                .deviceId(po.getDeviceId())
                .clientVersion(po.getClientVersion())
                .updateTime(po.getUpdateTime() == null ? null : po.getUpdateTime().getTime())
                .build();
    }

    private ContentPostPO toPostPO(ContentPostEntity entity) {
        ContentPostPO po = new ContentPostPO();
        po.setPostId(entity.getPostId());
        po.setUserId(entity.getUserId());
        po.setContentText(entity.getContentText());
        po.setMediaType(entity.getMediaType());
        po.setMediaInfo(entity.getMediaInfo());
        po.setLocationInfo(entity.getLocationInfo());
        po.setStatus(entity.getStatus());
        po.setVisibility(entity.getVisibility());
        po.setVersionNum(entity.getVersionNum());
        po.setIsEdited(Boolean.TRUE.equals(entity.getEdited()) ? 1 : 0);
        po.setCreateTime(entity.getCreateTime() == null ? null : new Date(entity.getCreateTime()));
        return po;
    }

    private ContentPostEntity toPostEntity(ContentPostPO po) {
        if (po == null) {
            return null;
        }
        return ContentPostEntity.builder()
                .postId(po.getPostId())
                .userId(po.getUserId())
                .contentText(po.getContentText())
                .mediaType(po.getMediaType())
                .mediaInfo(po.getMediaInfo())
                .locationInfo(po.getLocationInfo())
                .status(po.getStatus())
                .visibility(po.getVisibility())
                .versionNum(po.getVersionNum())
                .edited(Objects.equals(po.getIsEdited(), 1))
                .createTime(po.getCreateTime() == null ? null : po.getCreateTime().getTime())
                .build();
    }

    private ContentHistoryPO toHistoryPO(ContentHistoryEntity entity) {
        ContentHistoryPO po = new ContentHistoryPO();
        po.setHistoryId(entity.getHistoryId());
        po.setPostId(entity.getPostId());
        po.setVersionNum(entity.getVersionNum());
        po.setSnapshotContent(entity.getSnapshotContent());
        po.setSnapshotMedia(entity.getSnapshotMedia());
        po.setCreateTime(entity.getCreateTime() == null ? null : new Date(entity.getCreateTime()));
        return po;
    }

    private ContentHistoryEntity toHistoryEntity(ContentHistoryPO po) {
        if (po == null) {
            return null;
        }
        return ContentHistoryEntity.builder()
                .historyId(po.getHistoryId())
                .postId(po.getPostId())
                .versionNum(po.getVersionNum())
                .snapshotContent(po.getSnapshotContent())
                .snapshotMedia(po.getSnapshotMedia())
                .createTime(po.getCreateTime() == null ? null : po.getCreateTime().getTime())
                .build();
    }

    private ContentSchedulePO toSchedulePO(ContentScheduleEntity entity) {
        ContentSchedulePO po = new ContentSchedulePO();
        po.setTaskId(entity.getTaskId());
        po.setUserId(entity.getUserId());
        po.setContentData(entity.getContentData());
        po.setScheduleTime(entity.getScheduleTime() == null ? null : new Date(entity.getScheduleTime()));
        po.setStatus(entity.getStatus());
        po.setRetryCount(entity.getRetryCount());
        po.setIdempotentToken(entity.getIdempotentToken());
        po.setIsCanceled(entity.getIsCanceled());
        po.setLastError(entity.getLastError());
        po.setAlarmSent(entity.getAlarmSent());
        return po;
    }

    private ContentScheduleEntity toScheduleEntity(ContentSchedulePO po) {
        if (po == null) {
            return null;
        }
        return ContentScheduleEntity.builder()
                .taskId(po.getTaskId())
                .userId(po.getUserId())
                .contentData(po.getContentData())
                .scheduleTime(po.getScheduleTime() == null ? null : po.getScheduleTime().getTime())
                .status(po.getStatus())
                .retryCount(po.getRetryCount())
                .idempotentToken(po.getIdempotentToken())
                .isCanceled(po.getIsCanceled())
                .lastError(po.getLastError())
                .alarmSent(po.getAlarmSent())
                .build();
    }

}
