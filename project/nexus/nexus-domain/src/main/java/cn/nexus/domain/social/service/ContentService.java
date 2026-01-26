package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IContentDispatchPort;
import cn.nexus.domain.social.adapter.port.IContentRiskPort;
import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IMediaTranscodePort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IContentPublishAttemptRepository;
import cn.nexus.domain.social.model.entity.ContentDraftEntity;
import cn.nexus.domain.social.model.entity.ContentHistoryEntity;
import cn.nexus.domain.social.model.entity.ContentPublishAttemptEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.*;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 内容领域服务实现。
 */
@Service
@RequiredArgsConstructor
public class ContentService implements IContentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContentService.class);

    private final ISocialIdPort socialIdPort;
    private final IContentRepository contentRepository;
    private final IContentPublishAttemptRepository contentPublishAttemptRepository;
    private final IContentRiskPort contentRiskPort;
    private final IMediaStoragePort mediaStoragePort;
    private final IMediaTranscodePort mediaTranscodePort;
    private final IContentDispatchPort contentDispatchPort;
    private final RedissonClient redissonClient;

    private static final int STATUS_DRAFT = 0;
    private static final int STATUS_PENDING_REVIEW = 1;
    private static final int STATUS_PUBLISHED = 2;
    private static final int STATUS_REJECTED = 3;
    private static final int STATUS_DELETED = 6;

    // 定时任务状态（content_schedule）
    private static final int SCHEDULE_STATUS_SCHEDULED = 0;
    private static final int SCHEDULE_STATUS_PUBLISHED = 2;
    private static final int SCHEDULE_STATUS_CANCELED = 3;

    private static final long MAX_UPLOAD_SIZE_BYTES = 50L * 1024 * 1024; // 50MB 限制
    private static final List<String> ALLOWED_UPLOAD_TYPES = Arrays.asList("image/jpeg", "image/png", "image/jpg", "video/mp4", "application/octet-stream");

    private static final String POST_LOCK_KEY_PREFIX = "lock:content:post:";

    // 重试退避策略
    private static final int MAX_RETRY = 5;
    private static final long BACKOFF_BASE_MS = 5_000L;
    private static final long BACKOFF_MAX_MS = 5 * 60_000L;
    private static final double BACKOFF_JITTER_RATE = 0.2;

    /**
     * 上传会话：对基础参数做轻量校验后，委托媒体存储端口生成预签名上传凭证。
     */
    @Override
    public UploadSessionVO createUploadSession(String fileType, Long fileSize, String crc32) {
        if (fileSize != null && fileSize > MAX_UPLOAD_SIZE_BYTES) {
            throw new IllegalArgumentException("文件大小超出上限");
        }
        String normalizedType = fileType == null || fileType.isBlank() ? "application/octet-stream" : fileType.toLowerCase();
        if (!ALLOWED_UPLOAD_TYPES.contains(normalizedType)) {
            normalizedType = "application/octet-stream";
        }
        String sessionId = "session-" + socialIdPort.nextId();
        return mediaStoragePort.generateUploadSession(sessionId, normalizedType, fileSize, crc32);
    }

    /**
     * 草稿保存：生成 draftId，构建 ContentDraftEntity 持久化，并回传 draftId。未做内容校验与设备/版本约束，保持幂等由上层控制。
     */
    @Override
    public DraftVO saveDraft(Long userId, String contentText, List<String> mediaIds) {
        Long draftId = socialIdPort.nextId();
        ContentDraftEntity entity = ContentDraftEntity.builder()
                .draftId(draftId)
                .userId(userId)
                .draftContent(contentText)
                .mediaIds(mediaIds == null ? null : String.join(",", mediaIds))
                .deviceId("unknown")
                .clientVersion("1")
                .updateTime(socialIdPort.now())
                .build();
        contentRepository.saveDraft(entity);
        return DraftVO.builder().draftId(draftId).build();
    }

    /**
     * 内容发布主流程（Attempt 化：失败/处理中不影响当前可见版本）：
     * 1) 获取锁并校验作者权限；
     * 2) 创建发布 Attempt（含幂等 token 与输入快照）；
     * 3) 风控失败/转码未就绪：仅更新 Attempt，不更新 content_post，也不写 content_history；
     * 4) 仅当成功发布：写入 content_history + 更新 content_post + 分发事件，同时 Attempt -> PUBLISHED。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public OperationResultVO publish(Long postId, Long userId, String text, String mediaInfo, String location, String visibility, List<String> postTypes) {
        assertTx("publish");
        Long targetPostId = postId == null ? socialIdPort.nextId() : postId;
        RLock lock = lockFor(targetPostId);
        lock.lock();
        try {
            ContentPostEntity existedPost = contentRepository.findPostForUpdate(targetPostId);
            if (existedPost != null && userId != null && existedPost.getUserId() != null && !existedPost.getUserId().equals(userId)) {
                return OperationResultVO.builder().success(false).id(targetPostId).status("NO_PERMISSION").message("无权限").build();
            }

            List<String> normalizedPostTypes = normalizePostTypes(postTypes);
            String tokenSeed = "publish:" + (userId == null ? "0" : userId) + ":" + targetPostId + ":" +
                    (text == null ? "" : text) + ":" + (mediaInfo == null ? "" : mediaInfo) + ":" +
                    (location == null ? "" : location) + ":" + (visibility == null ? "" : visibility);
            if (postTypes != null) {
                tokenSeed = tokenSeed + ":" + String.join(",", normalizedPostTypes);
            }
            String token = hash(tokenSeed);

            ContentPublishAttemptEntity attempt = ContentPublishAttemptEntity.builder()
                    .attemptId(socialIdPort.nextId())
                    .postId(targetPostId)
                    .userId(userId)
                    .idempotentToken(token)
                    .transcodeJobId(null)
                    .attemptStatus(ContentPublishAttemptStatusEnumVO.CREATED.getCode())
                    .riskStatus(ContentPublishAttemptRiskStatusEnumVO.NOT_EVALUATED.getCode())
                    .transcodeStatus(ContentPublishAttemptTranscodeStatusEnumVO.NOT_STARTED.getCode())
                    .snapshotContent(text)
                    .snapshotMedia(mediaInfo)
                    .locationInfo(location)
                    .visibility(parseVisibility(visibility))
                    .publishedVersionNum(null)
                    .errorCode(null)
                    .errorMessage(null)
                    .createTime(socialIdPort.now())
                    .updateTime(socialIdPort.now())
                    .build();

            try {
                contentPublishAttemptRepository.create(attempt);
            } catch (DuplicateKeyException e) {
                ContentPublishAttemptEntity existAttempt = contentPublishAttemptRepository.findByToken(token);
                if (existAttempt != null) {
                    return toPublishResultFromAttempt(existAttempt);
                }
                throw e;
            }

            // 风控
            boolean passRisk = contentRiskPort.scanText(text) && contentRiskPort.scanMedia(mediaInfo);
            if (!passRisk) {
                boolean ok = contentPublishAttemptRepository.updateAttemptStatus(
                        attempt.getAttemptId(),
                        ContentPublishAttemptStatusEnumVO.RISK_REJECTED.getCode(),
                        ContentPublishAttemptRiskStatusEnumVO.REJECTED.getCode(),
                        ContentPublishAttemptTranscodeStatusEnumVO.NOT_STARTED.getCode(),
                        null,
                        null,
                        "RISK_REJECTED",
                        "风控拦截",
                        ContentPublishAttemptStatusEnumVO.CREATED.getCode());
                if (!ok) {
                    throw new IllegalStateException("Attempt 状态推进失败 attemptId=" + attempt.getAttemptId());
                }
                attempt.setAttemptStatus(ContentPublishAttemptStatusEnumVO.RISK_REJECTED.getCode());
                attempt.setRiskStatus(ContentPublishAttemptRiskStatusEnumVO.REJECTED.getCode());
                attempt.setErrorCode("RISK_REJECTED");
                attempt.setErrorMessage("风控拦截");
                return toPublishResultFromAttempt(attempt);
            }

            // 转码（占位实现：可同步 ready，也可返回 jobId 供异步推进）
            MediaTranscodeSubmitVO transcode = mediaTranscodePort.submit(mediaInfo);
            if (transcode == null) {
                transcode = MediaTranscodeSubmitVO.builder().ready(true).jobId(null).build();
            }
            if (!transcode.isReady()) {
                boolean ok = contentPublishAttemptRepository.updateAttemptStatus(
                        attempt.getAttemptId(),
                        ContentPublishAttemptStatusEnumVO.TRANSCODING.getCode(),
                        ContentPublishAttemptRiskStatusEnumVO.PASSED.getCode(),
                        ContentPublishAttemptTranscodeStatusEnumVO.PROCESSING.getCode(),
                        transcode.getJobId(),
                        null,
                        null,
                        "媒体处理中",
                        ContentPublishAttemptStatusEnumVO.CREATED.getCode());
                if (!ok) {
                    throw new IllegalStateException("Attempt 状态推进失败 attemptId=" + attempt.getAttemptId());
                }
                attempt.setAttemptStatus(ContentPublishAttemptStatusEnumVO.TRANSCODING.getCode());
                attempt.setRiskStatus(ContentPublishAttemptRiskStatusEnumVO.PASSED.getCode());
                attempt.setTranscodeStatus(ContentPublishAttemptTranscodeStatusEnumVO.PROCESSING.getCode());
                attempt.setTranscodeJobId(transcode.getJobId());
                attempt.setErrorMessage("媒体处理中");
                return toPublishResultFromAttempt(attempt);
            }

            // 仅成功发布才会推进可见版本
            int currentVersion = existedPost == null || existedPost.getVersionNum() == null ? 0 : existedPost.getVersionNum();
            int newVersion = currentVersion + 1;

            upsertPost(targetPostId, userId, text, mediaInfo, location, visibility, STATUS_PUBLISHED, newVersion, false);
            contentRepository.saveHistory(ContentHistoryEntity.builder()
                    .historyId(socialIdPort.nextId())
                    .postId(targetPostId)
                    .versionNum(newVersion)
                    .snapshotContent(text)
                    .snapshotMedia(mediaInfo)
                    .createTime(socialIdPort.now())
                    .build());

            boolean ok = contentPublishAttemptRepository.updateAttemptStatus(
                    attempt.getAttemptId(),
                    ContentPublishAttemptStatusEnumVO.PUBLISHED.getCode(),
                    ContentPublishAttemptRiskStatusEnumVO.PASSED.getCode(),
                    ContentPublishAttemptTranscodeStatusEnumVO.DONE.getCode(),
                    transcode.getJobId(),
                    newVersion,
                    null,
                    null,
                    ContentPublishAttemptStatusEnumVO.CREATED.getCode());
            if (!ok) {
                throw new IllegalStateException("Attempt 状态推进失败 attemptId=" + attempt.getAttemptId());
            }

            // 仅当入参提供了 postTypes 时，才进行覆盖写入；null 表示旧客户端不传，不破坏既有类型数据。
            if (postTypes != null) {
                contentRepository.replacePostTypes(targetPostId, normalizedPostTypes);
            }

            // 事务提交后再发 MQ：避免消费者读到未提交数据导致“索引误删”等线上鬼故事。
            dispatchAfterCommit(targetPostId, userId);
            return OperationResultVO.builder()
                    .success(true)
                    .id(targetPostId)
                    .attemptId(attempt.getAttemptId())
                    .versionNum(newVersion)
                    .status("PUBLISHED")
                    .message("发布成功")
                    .build();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 软删内容：按 userId 校验后设置删除状态，返回是否删除成功及状态文案。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO delete(Long userId, Long postId) {
        boolean ok = contentRepository.softDelete(postId, userId);
        if (ok) {
            dispatchDeleteAfterCommit(postId, userId);
        }
        return OperationResultVO.builder()
                .success(ok)
                .id(postId)
                .status(ok ? "DELETED" : "NOT_FOUND")
                .message(ok ? "已删除" : "未找到或无权限")
                .build();
    }

    /**
     * 定时发布创建：校验 userId，使用内容+时间生成幂等 token，若已有未执行任务直接返回；否则创建任务并处理并发主键冲突，返回任务状态。
     */
    @Override
    public OperationResultVO schedule(Long userId, String contentData, Long publishTime, String timezone) {
        if (userId == null) {
            return OperationResultVO.builder()
                    .success(false)
                    .status("USER_REQUIRED")
                    .message("userId 不能为空")
                    .build();
        }
        String token = hash(userId + ":" + (contentData == null ? "" : contentData) + ":" + publishTime);
        ContentScheduleEntity exist = contentRepository.findScheduleByToken(token);
        if (exist != null && (exist.getStatus() == null || exist.getStatus() == SCHEDULE_STATUS_SCHEDULED)) {
            return OperationResultVO.builder()
                    .success(true)
                    .id(exist.getTaskId())
                    .status("SCHEDULED_DUPLICATE")
                    .message("已存在同内容定时任务")
                    .build();
        }
        Long taskId = socialIdPort.nextId();
        try {
            contentRepository.createSchedule(ContentScheduleEntity.builder()
                    .taskId(taskId)
                    .userId(userId)
                    .contentData(contentData)
                    .scheduleTime(publishTime)
                    .status(SCHEDULE_STATUS_SCHEDULED)
                    .retryCount(0)
                    .idempotentToken(token)
                    .isCanceled(0)
                    .alarmSent(0)
                    .build());
        } catch (DuplicateKeyException e) {
            ContentScheduleEntity duplicated = contentRepository.findScheduleByToken(token);
            if (duplicated != null) {
                return OperationResultVO.builder()
                        .success(true)
                        .id(duplicated.getTaskId())
                        .status("SCHEDULED_DUPLICATE")
                        .message("已存在同内容定时任务")
                        .build();
            }
            throw e;
        }
        return OperationResultVO.builder()
                .success(true)
                .id(taskId)
                .status("SCHEDULED")
                .message("定时任务已创建")
                .build();
    }

    /**
     * 客户端草稿同步：查找草稿并基于客户端版本号判定是否可覆盖，更新内容/设备/版本并回写；不存在则创建新草稿。
     */
    @Override
    public OperationResultVO syncDraft(Long draftId, String diffContent, String clientVersion, String deviceId, List<String> mediaIds) {
        ContentDraftEntity entity = contentRepository.findDraft(draftId);
        if (entity == null) {
            entity = ContentDraftEntity.builder()
                    .draftId(draftId)
                    .draftContent(diffContent)
                    .mediaIds(mediaIds == null ? null : String.join(",", mediaIds))
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
            if (mediaIds != null) {
                entity.setMediaIds(String.join(",", mediaIds));
            }
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

    /**
     * 内容历史查询：校验访问权限，分页拉取版本快照，输出下一游标。
     */
    @Override
    public ContentHistoryVO history(Long postId, Long userId, Integer limit, Integer offset) {
        ContentPostEntity post = contentRepository.findPost(postId);
        if (post != null && userId != null && post.getUserId() != null && !post.getUserId().equals(userId)) {
            log.warn("history access denied postId=" + postId + ", requestUser=" + userId + ", owner=" + post.getUserId());
            return ContentHistoryVO.builder().versions(Collections.emptyList()).nextCursor(null).status("NO_PERMISSION").build();
        }
        long start = System.currentTimeMillis();
        int pageSize = limit == null ? 20 : Math.min(limit, 100);
        int cursor = offset == null ? 0 : Math.max(0, offset);
        List<ContentHistoryEntity> histories = contentRepository.listHistory(postId, pageSize + 1, cursor);
        if (histories.isEmpty()) {
            return ContentHistoryVO.builder().versions(Collections.emptyList()).nextCursor(null).build();
        }
        histories.sort(Comparator.comparing(ContentHistoryEntity::getVersionNum));
        List<ContentHistoryVO.ContentVersionVO> versions = new ArrayList<>();
        boolean hasMore = histories.size() > pageSize;
        List<ContentHistoryEntity> page = histories.size() > pageSize ? histories.subList(0, pageSize) : histories;
        for (ContentHistoryEntity history : page) {
            versions.add(ContentHistoryVO.ContentVersionVO.builder()
                    .versionId(history.getVersionNum() == null ? null : history.getVersionNum().longValue())
                    .content(history.getSnapshotContent())
                    .time(history.getCreateTime())
                    .build());
        }
        long cost = System.currentTimeMillis() - start;
        if (cost > 1500L) {
            log.warn("history query slow postId=" + postId + ", cost=" + cost + "ms, limit=" + pageSize);
        }
        Integer nextCursor = hasMore ? cursor + pageSize : null;
        return ContentHistoryVO.builder().versions(versions).nextCursor(nextCursor).build();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    /**
     * 内容回滚：在事务与行锁下校验权限，定位目标版本快照并生成新版本，更新帖子状态与历史记录。
     */
    public OperationResultVO rollback(Long postId, Long userId, Long targetVersionId) {
        assertTx("rollback");
        RLock lock = lockFor(postId);
        lock.lock();
        try {
            ContentPostEntity post = contentRepository.findPostForUpdate(postId);
            if (post != null && post.getUserId() != null && userId != null && !post.getUserId().equals(userId)) {
                return OperationResultVO.builder()
                        .success(false)
                        .id(postId)
                        .status("NO_PERMISSION")
                        .message("无权限回滚该内容")
                        .build();
            }
            ContentHistoryEntity target = contentRepository.findHistoryVersion(postId, targetVersionId == null ? null : targetVersionId.intValue());
            if (target == null) {
                return OperationResultVO.builder()
                        .success(false)
                        .id(postId)
                        .status("VERSION_NOT_FOUND")
                        .message("目标版本不存在")
                        .build();
            }
            String content = target.getSnapshotContent();
            int currentVersion = post == null || post.getVersionNum() == null ? 0 : post.getVersionNum();
            int newVersion = currentVersion + 1;

            boolean ok = contentRepository.updatePostStatusAndContent(
                    postId,
                    STATUS_PUBLISHED,
                    newVersion,
                    true,
                    content,
                    post == null ? null : post.getMediaInfo(),
                    post == null ? null : post.getLocationInfo(),
                    post == null ? 0 : post.getVisibility());
            contentRepository.saveHistory(cn.nexus.domain.social.model.entity.ContentHistoryEntity.builder()
                    .historyId(socialIdPort.nextId())
                    .postId(postId)
                    .versionNum(newVersion)
                    .snapshotContent(content)
                    .snapshotMedia(post == null ? null : post.getMediaInfo())
                    .createTime(socialIdPort.now())
                    .build());
            return OperationResultVO.builder()
                    .success(ok)
                    .id(postId)
                    .status(ok ? "ROLLED_BACK" : "ROLLBACK_FAIL")
                    .message(ok ? "已回滚" : "回滚失败")
                    .build();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 批量执行到期定时任务：按时间查询待处理任务，跳过已取消，逐条调用 publish 并按结果更新状态与重试计数，超过重试上限则标记失败报警。
     */
    @Override
    public OperationResultVO processSchedules(Long now, Integer limit) {
        long ts = now == null ? socialIdPort.now() : now;
        List<ContentScheduleEntity> tasks = contentRepository.listPendingSchedules(ts, limit == null ? 50 : limit);
        int success = 0;
        for (ContentScheduleEntity task : tasks) {
            if (task.getIsCanceled() != null && task.getIsCanceled() == 1) {
                contentRepository.updateScheduleStatus(task.getTaskId(), SCHEDULE_STATUS_CANCELED, task.getRetryCount(), "已取消跳过", 0, null, SCHEDULE_STATUS_SCHEDULED);
                continue;
            }
            OperationResultVO res = publish(null, task.getUserId(), task.getContentData(), null, null, "PUBLIC", null);
            if (res.isSuccess()) {
                success++;
                contentRepository.updateScheduleStatus(task.getTaskId(), SCHEDULE_STATUS_PUBLISHED, task.getRetryCount(), null, 0, null, SCHEDULE_STATUS_SCHEDULED);
            } else {
                int currentRetry = task.getRetryCount() == null ? 0 : task.getRetryCount();
                int nextRetry = currentRetry + 1;
                boolean exceed = nextRetry >= MAX_RETRY;
                long delayMs = exceed ? 0L : calcNextDelayMs(nextRetry);
                Long nextTime = exceed ? null : ts + delayMs;
                Integer alarm = exceed ? 1 : 0;
                String err = res.getMessage();
                contentRepository.updateScheduleStatus(task.getTaskId(),
                        exceed ? SCHEDULE_STATUS_CANCELED : SCHEDULE_STATUS_SCHEDULED,
                        nextRetry,
                        err,
                        alarm,
                        nextTime,
                        SCHEDULE_STATUS_SCHEDULED);
                if (exceed) {
                    log.error("schedule task reach max retry, taskId=" + task.getTaskId() + ", retry=" + nextRetry + ", err=" + err);
                }
            }
        }
        return OperationResultVO.builder()
                .success(true)
                .status("SCHEDULED_RUN")
                .message("processed=" + success + "/" + tasks.size())
                .build();
    }

    /**
     * 单个定时任务执行：校验状态/取消标记，调用 publish 产出，按结果更新重试/延迟或置成功/终止。
     */
    @Override
    public OperationResultVO executeSchedule(Long taskId) {
        ContentScheduleEntity task = contentRepository.findSchedule(taskId);
        if (task == null) {
            return OperationResultVO.builder().success(false).status("NOT_FOUND").message("任务不存在").build();
        }
        if (task.getStatus() != null && task.getStatus() != SCHEDULE_STATUS_SCHEDULED) {
            return OperationResultVO.builder().success(false).status("SKIPPED").message("状态不允许执行").build();
        }
        if (task.getIsCanceled() != null && task.getIsCanceled() == 1) {
            return OperationResultVO.builder().success(false).status("CANCELED").message("任务已取消").build();
        }
        OperationResultVO res = publish(null, task.getUserId(), task.getContentData(), null, null, "PUBLIC", null);
        if (res.isSuccess()) {
            contentRepository.updateScheduleStatus(taskId, SCHEDULE_STATUS_PUBLISHED, task.getRetryCount(), null, 0, null, SCHEDULE_STATUS_SCHEDULED);
        } else {
            int currentRetry = task.getRetryCount() == null ? 0 : task.getRetryCount();
            int nextRetry = currentRetry + 1;
            boolean exceed = nextRetry >= MAX_RETRY;
            long delayMs = exceed ? 0L : calcNextDelayMs(nextRetry);
            Long nextTime = exceed ? null : socialIdPort.now() + delayMs;
            Integer alarm = exceed ? 1 : 0;
            contentRepository.updateScheduleStatus(taskId, exceed ? SCHEDULE_STATUS_CANCELED : SCHEDULE_STATUS_SCHEDULED, nextRetry, res.getMessage(), alarm, nextTime, SCHEDULE_STATUS_SCHEDULED);
            if (exceed) {
                log.error("execute schedule reach max retry, taskId=" + taskId + ", retry=" + nextRetry + ", err=" + res.getMessage());
            }
        }
        return res;
    }

    /**
     * 取消定时任务：校验归属与存在性，设置取消原因并返回操作结果。
     */
    @Override
    public OperationResultVO cancelSchedule(Long taskId, Long userId, String reason) {
        ContentScheduleEntity task = contentRepository.findSchedule(taskId);
        if (task == null) {
            return OperationResultVO.builder().success(false).status("NOT_FOUND").message("任务不存在").build();
        }
        if (userId == null || task.getUserId() == null || !task.getUserId().equals(userId)) {
            return OperationResultVO.builder().success(false).status("NO_PERMISSION").message("无权限取消该任务").build();
        }
        boolean ok = contentRepository.cancelSchedule(taskId, userId, reason);
        return OperationResultVO.builder()
                .success(ok)
                .id(taskId)
                .status(ok ? "CANCELED" : "CANCEL_FAIL")
                .message(ok ? (reason == null ? "已取消" : reason) : "任务不存在或状态不允许")
                .build();
    }

    /**
     * 更新定时任务：校验归属/状态/取消标志，重算幂等 token 后更新时间与内容，返回更新是否成功。
     */
    @Override
    public OperationResultVO updateSchedule(Long taskId, Long userId, Long publishTime, String contentData, String reason) {
        ContentScheduleEntity task = contentRepository.findSchedule(taskId);
        if (task == null) {
            return OperationResultVO.builder().success(false).status("NOT_FOUND").message("任务不存在").build();
        }
        if (userId == null || task.getUserId() == null || !task.getUserId().equals(userId)) {
            return OperationResultVO.builder().success(false).status("NO_PERMISSION").message("无权限变更该任务").build();
        }
        if (task.getIsCanceled() != null && task.getIsCanceled() == 1) {
            return OperationResultVO.builder().success(false).status("CANCELED").message("任务已取消").build();
        }
        if (task.getStatus() != null && task.getStatus() != SCHEDULE_STATUS_SCHEDULED) {
            return OperationResultVO.builder().success(false).status("SKIPPED").message("状态不允许变更").build();
        }
        String token = hash((contentData == null ? task.getContentData() : contentData) + ":" + publishTime);
        boolean ok = contentRepository.updateSchedule(taskId, userId, publishTime, contentData, token, reason);
        return OperationResultVO.builder()
                .success(ok)
                .id(taskId)
                .status(ok ? "UPDATED" : "UPDATE_FAIL")
                .message(ok ? "已更新定时任务" : "更新失败")
                .build();
    }

    /**
     * 审计接口：返回指定任务详情，若非任务拥有者则返回空，供外部审批查看。
     */
    @Override
    public ContentScheduleEntity getScheduleAudit(Long taskId, Long userId) {
        ContentScheduleEntity task = contentRepository.findSchedule(taskId);
        if (task == null) {
            return null;
        }
        if (userId != null && task.getUserId() != null && !task.getUserId().equals(userId)) {
            return null;
        }
        return task;
    }

    @Override
    public ContentPublishAttemptEntity getPublishAttemptAudit(Long attemptId, Long userId) {
        if (attemptId == null) {
            return null;
        }
        ContentPublishAttemptEntity attempt = contentPublishAttemptRepository.findByAttemptId(attemptId);
        if (attempt == null) {
            return null;
        }
        if (userId != null && attempt.getUserId() != null && !attempt.getUserId().equals(userId)) {
            return null;
        }
        return attempt;
    }

    /**
     * 存储重平衡：全量快照模式下无需重平衡，保留接口以兼容调用方。
     */
    @Override
    public OperationResultVO rebalanceStorage(Long postId) {
        List<ContentHistoryEntity> histories = contentRepository.listHistory(postId, 1, 0);
        if (histories.isEmpty()) {
            return OperationResultVO.builder().success(false).status("EMPTY").message("无版本可重平衡").build();
        }
        return OperationResultVO.builder().success(true).status("SKIP").message("全量快照无需重平衡").build();
    }

    private int deriveMediaType(String mediaInfo) {
        if (mediaInfo == null || mediaInfo.trim().isEmpty()) {
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
        switch (visibility.toUpperCase()) {
            case "FRIEND":
                return 1;
            case "PRIVATE":
                return 2;
            default:
                return 0;
        }
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

    private String hash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] h = digest.digest(data);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }

    /**
     * 便捷的字符串哈希，统一使用 UTF-8，避免调用方误用 byte[] 签名导致报错。
     */
    private String hash(String text) {
        if (text == null) {
            text = "";
        }
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }

    private void upsertPost(Long postId, Long userId, String text, String mediaInfo, String location, String visibility, int status, int versionNum, boolean edited) {
        ContentPostEntity post = contentRepository.findPost(postId);
        if (post == null) {
            post = ContentPostEntity.builder()
                    .postId(postId)
                    .userId(userId)
                    .contentText(text)
                    .mediaType(deriveMediaType(mediaInfo))
                    .mediaInfo(mediaInfo)
                    .locationInfo(location)
                    .status(status)
                    .visibility(parseVisibility(visibility))
                    .versionNum(versionNum)
                    .edited(edited)
                    .createTime(socialIdPort.now())
                    .build();
            contentRepository.savePost(post);
        } else {
            if (userId != null && post.getUserId() != null && !post.getUserId().equals(userId)) {
                throw new IllegalStateException("post 所属用户不匹配");
            }
            boolean ok = contentRepository.updatePostStatusAndContent(postId, status, versionNum, edited, text, mediaInfo, location, parseVisibility(visibility));
            if (!ok) {
                throw new IllegalStateException("post 更新失败，可能存在版本冲突 postId=" + postId);
            }
        }
    }

    private List<String> normalizePostTypes(List<String> postTypes) {
        if (postTypes == null) {
            return null;
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : postTypes) {
            if (raw == null) {
                continue;
            }
            String type = raw.trim();
            if (type.isEmpty()) {
                continue;
            }
            if (normalized.contains(type)) {
                continue;
            }
            normalized.add(type);
            if (normalized.size() >= 5) {
                break;
            }
        }
        return normalized;
    }

    private RLock lockFor(Long postId) {
        Long key = postId == null ? -1L : postId;
        return redissonClient.getLock(POST_LOCK_KEY_PREFIX + key);
    }

    private void assertTx(String scene) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("事务未开启，禁止在非事务环境下调用 " + scene);
        }
    }

    private void dispatchAfterCommit(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return;
        }
        // 理论上 publish() 必须在事务内；这里做防御，避免未来改动把事件又发回“事务外”。
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            contentDispatchPort.onPublished(postId, userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    contentDispatchPort.onPublished(postId, userId);
                } catch (Exception e) {
                    // afterCommit 失败不会回滚业务事务：只能记录，后续再补偿（如需要可引入 outbox）。
                    log.error("post published dispatch failed after commit, postId={}, userId={}", postId, userId, e);
                }
            }
        });
    }

    private void dispatchDeleteAfterCommit(Long postId, Long operatorId) {
        if (postId == null || operatorId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            contentDispatchPort.onDeleted(postId, operatorId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    contentDispatchPort.onDeleted(postId, operatorId);
                } catch (Exception e) {
                    log.error("post deleted dispatch failed after commit, postId={}, operatorId={}", postId, operatorId, e);
                }
            }
        });
    }

    private OperationResultVO toPublishResultFromAttempt(ContentPublishAttemptEntity attempt) {
        if (attempt == null) {
            return OperationResultVO.builder().success(false).status("ATTEMPT_NOT_FOUND").message("发布尝试不存在").build();
        }
        Integer attemptStatus = attempt.getAttemptStatus();
        if (attemptStatus != null && attemptStatus == ContentPublishAttemptStatusEnumVO.PUBLISHED.getCode()) {
            return OperationResultVO.builder()
                    .success(true)
                    .id(attempt.getPostId())
                    .attemptId(attempt.getAttemptId())
                    .versionNum(attempt.getPublishedVersionNum())
                    .status("PUBLISHED")
                    .message("发布成功")
                    .build();
        }
        if (attemptStatus != null && attemptStatus == ContentPublishAttemptStatusEnumVO.RISK_REJECTED.getCode()) {
            return OperationResultVO.builder()
                    .success(false)
                    .id(attempt.getPostId())
                    .attemptId(attempt.getAttemptId())
                    .status("REJECTED")
                    .message(attempt.getErrorMessage() == null ? "风控拦截" : attempt.getErrorMessage())
                    .build();
        }
        if (attemptStatus != null && attemptStatus == ContentPublishAttemptStatusEnumVO.TRANSCODING.getCode()) {
            return OperationResultVO.builder()
                    .success(false)
                    .id(attempt.getPostId())
                    .attemptId(attempt.getAttemptId())
                    .status("PROCESSING")
                    .message(attempt.getErrorMessage() == null ? "媒体处理中" : attempt.getErrorMessage())
                    .build();
        }
        if (attemptStatus != null && attemptStatus == ContentPublishAttemptStatusEnumVO.FAILED.getCode()) {
            return OperationResultVO.builder()
                    .success(false)
                    .id(attempt.getPostId())
                    .attemptId(attempt.getAttemptId())
                    .status("FAILED")
                    .message(attempt.getErrorMessage() == null ? "系统失败" : attempt.getErrorMessage())
                    .build();
        }
        return OperationResultVO.builder()
                .success(false)
                .id(attempt.getPostId())
                .attemptId(attempt.getAttemptId())
                .status("CREATED")
                .message("处理中")
                .build();
    }

    private long calcNextDelayMs(int retryCount) {
        long exp = (long) (BACKOFF_BASE_MS * Math.pow(2, Math.min(retryCount, 10)));
        long jitter = (long) (exp * BACKOFF_JITTER_RATE * (Math.random() * 2 - 1));
        long candidate = exp + jitter;
        return Math.max(BACKOFF_BASE_MS, Math.min(BACKOFF_MAX_MS, candidate));
    }
}
