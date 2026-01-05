package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IContentDispatchPort;
import cn.nexus.domain.social.adapter.port.IContentRiskPort;
import cn.nexus.domain.social.adapter.port.IMediaTranscodePort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentDraftEntity;
import cn.nexus.domain.social.model.entity.ContentHistoryEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 内容领域服务实现。
 */
@Service
@RequiredArgsConstructor
public class ContentService implements IContentService {

    private final ISocialIdPort socialIdPort;
    private final IContentRepository contentRepository;
    private final IContentRiskPort contentRiskPort;
    private final IMediaTranscodePort mediaTranscodePort;
    private final IContentDispatchPort contentDispatchPort;

    private static final int STATUS_DRAFT = 0;
    private static final int STATUS_PENDING_REVIEW = 1;
    private static final int STATUS_PUBLISHED = 2;
    private static final int STATUS_REJECTED = 3;
    private static final int STATUS_SCHEDULED = 4;
    private static final int STATUS_DELETED = 6;

    @Override
    public UploadSessionVO createUploadSession(String fileType, Long fileSize, String crc32) {
        return UploadSessionVO.builder()
                .uploadUrl("https://mock-upload/" + socialIdPort.nextId())
                .token("token-" + socialIdPort.nextId())
                .sessionId("session-" + socialIdPort.nextId())
                .build();
    }

    @Override
    public DraftVO saveDraft(Long userId, String contentText, List<String> mediaIds) {
        Long draftId = socialIdPort.nextId();
        ContentDraftEntity entity = ContentDraftEntity.builder()
                .draftId(draftId)
                .userId(userId)
                .draftContent(contentText)
                .deviceId("unknown")
                .clientVersion("1")
                .updateTime(socialIdPort.now())
                .build();
        contentRepository.saveDraft(entity);
        return DraftVO.builder().draftId(draftId).build();
    }

    @Override
    public PublishResultVO publish(Long userId, String text, String mediaInfo, String location, String visibility) {
        Long postId = socialIdPort.nextId();
        ContentPostEntity post = ContentPostEntity.builder()
                .postId(postId)
                .userId(userId)
                .contentText(text)
                .mediaType(deriveMediaType(mediaInfo))
                .mediaInfo(mediaInfo)
                .locationInfo(location)
                .status(STATUS_PENDING_REVIEW)
                .visibility(parseVisibility(visibility))
                .versionNum(1)
                .edited(false)
                .createTime(socialIdPort.now())
                .build();
        contentRepository.savePost(post);
        contentRepository.saveHistory(ContentHistoryEntity.builder()
                .historyId(socialIdPort.nextId())
                .postId(postId)
                .versionNum(1)
                .snapshotContent(text)
                .snapshotMedia(mediaInfo)
                .createTime(socialIdPort.now())
                .build());
        boolean passRisk = contentRiskPort.scanText(text) && contentRiskPort.scanMedia(mediaInfo);
        if (!passRisk) {
            contentRepository.updatePostStatusAndContent(postId, STATUS_REJECTED, 1, false, text, mediaInfo, location, post.getVisibility());
            return PublishResultVO.builder().postId(postId).status("REJECTED").build();
        }
        boolean mediaReady = mediaTranscodePort.transcode(mediaInfo);
        if (!mediaReady) {
            return PublishResultVO.builder().postId(postId).status("PROCESSING").build();
        }
        contentRepository.updatePostStatusAndContent(postId, STATUS_PUBLISHED, 1, false, text, mediaInfo, location, post.getVisibility());
        contentDispatchPort.onPublished(postId, userId);
        return PublishResultVO.builder().postId(postId).status("PUBLISHED").build();
    }

    @Override
    public OperationResultVO delete(Long userId, Long postId) {
        boolean ok = contentRepository.softDelete(postId, userId);
        return OperationResultVO.builder()
                .success(ok)
                .id(postId)
                .status(ok ? "DELETED" : "NOT_FOUND")
                .message(ok ? "已删除" : "未找到或无权限")
                .build();
    }

    @Override
    public OperationResultVO schedule(String contentData, Long publishTime, String timezone) {
        Long taskId = socialIdPort.nextId();
        contentRepository.createSchedule(ContentScheduleEntity.builder()
                .taskId(taskId)
                .userId(null)
                .contentData(contentData)
                .scheduleTime(publishTime)
                .status(STATUS_SCHEDULED)
                .retryCount(0)
                .build());
        return OperationResultVO.builder()
                .success(true)
                .id(taskId)
                .status("SCHEDULED")
                .message("定时任务已创建")
                .build();
    }

    @Override
    public OperationResultVO syncDraft(Long draftId, String diffContent, String clientVersion, String deviceId) {
        ContentDraftEntity entity = contentRepository.findDraft(draftId);
        if (entity == null) {
            entity = ContentDraftEntity.builder()
                    .draftId(draftId)
                    .draftContent(diffContent)
                    .deviceId(deviceId)
                    .clientVersion(clientVersion)
                    .updateTime(socialIdPort.now())
                    .build();
        } else {
            if (!isNewerClientVersion(clientVersion, entity.getClientVersion())) {
                return OperationResultVO.builder()
                        .success(false)
                        .id(draftId)
                        .status("STALE_VERSION")
                        .message("客户端版本过旧，拒绝覆盖")
                        .build();
            }
            entity.setDraftContent(diffContent);
            entity.setDeviceId(deviceId);
            entity.setClientVersion(clientVersion);
            entity.setUpdateTime(socialIdPort.now());
        }
        contentRepository.saveDraft(entity);
        return OperationResultVO.builder()
                .success(true)
                .id(draftId)
                .status("SYNCED")
                .message("serverVersion-" + clientVersion)
                .build();
    }

    @Override
    public ContentHistoryVO history(Long postId, Long userId, Integer limit) {
        List<ContentHistoryVO.ContentVersionVO> versions = contentRepository.listHistory(postId, limit).stream()
                .map(v -> ContentHistoryVO.ContentVersionVO.builder()
                        .versionId(v.getVersionNum().longValue())
                        .content(v.getSnapshotContent())
                        .time(v.getCreateTime())
                        .build())
                .collect(Collectors.toList());
        return ContentHistoryVO.builder().versions(versions).build();
    }

    @Override
    public OperationResultVO rollback(Long postId, Long targetVersionId) {
        ContentHistoryEntity target = contentRepository.findHistoryVersion(postId, targetVersionId == null ? null : targetVersionId.intValue());
        if (target == null) {
            return OperationResultVO.builder()
                    .success(false)
                    .id(postId)
                    .status("VERSION_NOT_FOUND")
                    .message("目标版本不存在")
                    .build();
        }
        ContentPostEntity post = contentRepository.findPost(postId);
        int newVersion = (post == null || post.getVersionNum() == null ? target.getVersionNum() : post.getVersionNum() + 1);
        boolean ok = contentRepository.updatePostStatusAndContent(
                postId,
                STATUS_PUBLISHED,
                newVersion,
                true,
                target.getSnapshotContent(),
                target.getSnapshotMedia(),
                post == null ? null : post.getLocationInfo(),
                post == null ? 0 : post.getVisibility());
        contentRepository.saveHistory(ContentHistoryEntity.builder()
                .historyId(socialIdPort.nextId())
                .postId(postId)
                .versionNum(newVersion)
                .snapshotContent(target.getSnapshotContent())
                .snapshotMedia(target.getSnapshotMedia())
                .createTime(socialIdPort.now())
                .build());
        return OperationResultVO.builder()
                .success(ok)
                .id(postId)
                .status(ok ? "ROLLED_BACK" : "ROLLBACK_FAIL")
                .message(ok ? "已回滚" : "回滚失败")
                .build();
    }

    @Override
    public OperationResultVO processSchedules(Long now, Integer limit) {
        long ts = now == null ? socialIdPort.now() : now;
        List<ContentScheduleEntity> tasks = contentRepository.listPendingSchedules(ts, limit == null ? 50 : limit);
        int success = 0;
        for (ContentScheduleEntity task : tasks) {
            OperationResultVO res = publish(task.getUserId(), task.getContentData(), null, null, "PUBLIC");
            if (res.isSuccess()) {
                success++;
                contentRepository.updateScheduleStatus(task.getTaskId(), STATUS_PUBLISHED, task.getRetryCount());
            } else {
                contentRepository.updateScheduleStatus(task.getTaskId(), STATUS_PENDING_REVIEW, task.getRetryCount() + 1);
            }
        }
        return OperationResultVO.builder()
                .success(true)
                .status("SCHEDULED_RUN")
                .message("processed=" + success + "/" + tasks.size())
                .build();
    }

    private int deriveMediaType(String mediaInfo) {
        if (mediaInfo == null || mediaInfo.isBlank()) {
            return 0;
        }
        if (mediaInfo.contains("video")) {
            return 2;
        }
        if (mediaInfo.contains("image")) {
            return 1;
        }
        return 0;
    }

    private int parseVisibility(String visibility) {
        if (visibility == null) {
            return 0;
        }
        return switch (visibility.toUpperCase()) {
            case "FRIEND" -> 1;
            case "PRIVATE" -> 2;
            default -> 0;
        };
    }

    private boolean isNewerClientVersion(String incoming, String current) {
        try {
            long in = incoming == null ? 0L : Long.parseLong(incoming);
            long cur = current == null ? 0L : Long.parseLong(current);
            return in >= cur;
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
