package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IContentEventOutboxPort;
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
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
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
    private final IMediaStoragePort mediaStoragePort;
    private final IMediaTranscodePort mediaTranscodePort;
    private final IContentEventOutboxPort contentEventOutboxPort;
    private final IRiskService riskService;
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
     * 草稿保存/覆盖更新：draftId 为空则创建新草稿，否则覆盖更新同一条草稿（draftId=postId）。
     */
    @Override
    public DraftVO saveDraft(Long userId, Long draftId, String contentText, List<String> mediaIds) {
        validateContentNotEmpty(contentText, mediaIds, null);
        Long targetDraftId = draftId == null ? socialIdPort.nextId() : draftId;
        assertNotScheduled(targetDraftId);
        ContentDraftEntity existed = contentRepository.findDraft(targetDraftId);
        if (existed != null && existed.getUserId() != null && userId != null && !existed.getUserId().equals(userId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "NO_PERMISSION");
        }

        ContentDraftEntity entity = ContentDraftEntity.builder()
                .draftId(targetDraftId)
                .userId(userId)
                .draftContent(contentText)
                .mediaIds(mediaIds == null ? null : String.join(",", mediaIds))
                .deviceId("unknown")
                .clientVersion(1L)
                .updateTime(socialIdPort.now())
                .build();
        contentRepository.saveDraft(entity);
        return DraftVO.builder().draftId(targetDraftId).build();
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
        assertNotScheduled(postId);
        return publishInternal(postId, userId, text, mediaInfo, location, visibility, postTypes);
    }

    private OperationResultVO publishInternal(Long postId, Long userId, String text, String mediaInfo, String location, String visibility, List<String> postTypes) {
        assertTx("publish");
        validateContentNotEmpty(text, null, mediaInfo);
        if (postId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "postId 不能为空，请先创建草稿拿 postId");
        }
        Long targetPostId = postId;
        RLock lock = lockFor(targetPostId);
        lock.lock();
        try {
            ContentPostEntity existedPost = contentRepository.findPostForUpdate(targetPostId);
            if (existedPost != null && userId != null && existedPost.getUserId() != null && !existedPost.getUserId().equals(userId)) {
                return OperationResultVO.builder().success(false).id(targetPostId).status("NO_PERMISSION").message("无权限").build();
            }
            if (existedPost != null && existedPost.getStatus() != null && existedPost.getStatus() == STATUS_DELETED) {
                return OperationResultVO.builder().success(false).id(targetPostId).status("DELETED").message("已删除/禁止发布").build();
            }

            ContentPublishAttemptEntity activeAttempt = contentPublishAttemptRepository.findLatestActiveAttempt(targetPostId, userId);
            if (activeAttempt != null) {
                return toPublishResultFromAttempt(activeAttempt);
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

            // 风控：统一决策入口，保证每次发布请求都有审计落库 + REVIEW 自动进入工单/异步扫描。
            String riskEventId = token;
            String riskActionType = existedPost == null ? "PUBLISH_POST" : "EDIT_POST";
            RiskEventVO riskEvent = RiskEventVO.builder()
                    .eventId(riskEventId)
                    .userId(userId)
                    .actionType(riskActionType)
                    .scenario("post.publish")
                    .contentText(text)
                    .mediaUrls(toMediaUrls(mediaInfo))
                    .targetId(String.valueOf(targetPostId))
                    .extJson("{\"biz\":\"content\",\"postId\":" + targetPostId + ",\"attemptId\":" + attempt.getAttemptId() + "}")
                    .occurTime(socialIdPort.now())
                    .build();
            RiskDecisionVO riskDecision = riskService.decision(riskEvent);
            String riskResult = riskDecision == null ? "PASS" : riskDecision.getResult();

            if ("BLOCK".equalsIgnoreCase(riskResult) || "LIMIT".equalsIgnoreCase(riskResult) || "CHALLENGE".equalsIgnoreCase(riskResult)) {
                boolean ok = contentPublishAttemptRepository.updateAttemptStatus(
                        attempt.getAttemptId(),
                        ContentPublishAttemptStatusEnumVO.RISK_REJECTED.getCode(),
                        ContentPublishAttemptRiskStatusEnumVO.REJECTED.getCode(),
                        ContentPublishAttemptTranscodeStatusEnumVO.NOT_STARTED.getCode(),
                        null,
                        null,
                        riskDecision == null ? "RISK_REJECTED" : riskDecision.getReasonCode(),
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

            if ("REVIEW".equalsIgnoreCase(riskResult)) {
                int currentVersion = existedPost == null || existedPost.getVersionNum() == null ? 0 : existedPost.getVersionNum();
                int newVersion = currentVersion + 1;

                upsertPost(targetPostId, userId, text, mediaInfo, location, visibility, STATUS_PENDING_REVIEW, newVersion, existedPost != null);
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
                        ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode(),
                        ContentPublishAttemptRiskStatusEnumVO.REVIEW_REQUIRED.getCode(),
                        ContentPublishAttemptTranscodeStatusEnumVO.NOT_STARTED.getCode(),
                        null,
                        newVersion,
                        riskDecision == null ? "RISK_REVIEW" : riskDecision.getReasonCode(),
                        "内容审核中",
                        ContentPublishAttemptStatusEnumVO.CREATED.getCode());
                if (!ok) {
                    throw new IllegalStateException("Attempt 状态推进失败 attemptId=" + attempt.getAttemptId());
                }
                attempt.setAttemptStatus(ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode());
                attempt.setRiskStatus(ContentPublishAttemptRiskStatusEnumVO.REVIEW_REQUIRED.getCode());
                attempt.setPublishedVersionNum(newVersion);
                attempt.setErrorCode(riskDecision == null ? "RISK_REVIEW" : riskDecision.getReasonCode());
                attempt.setErrorMessage("内容审核中");

                if (postTypes != null) {
                    contentRepository.replacePostTypes(targetPostId, normalizedPostTypes);
                }
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
            if (existedPost == null) {
                dispatchAfterCommit(targetPostId, userId, newVersion);
            } else {
                dispatchUpdateAfterCommit(targetPostId, userId, newVersion);
            }
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

    private List<String> toMediaUrls(String mediaInfo) {
        if (mediaInfo == null || mediaInfo.isBlank()) {
            return List.of();
        }
        String[] parts = mediaInfo.split(",");
        List<String> urls = new ArrayList<>();
        for (String raw : parts) {
            if (raw == null) {
                continue;
            }
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            // 约定：mediaInfo 可直接是 URL，也可以是对象存储的 sessionId/objectKey（由异步 worker 转换为可读 URL）。
            urls.add(token);
        }
        return urls;
    }

    private void validateContentNotEmpty(String text, List<String> mediaIds, String mediaInfo) {
        boolean hasText = text != null && !text.trim().isEmpty();
        boolean hasMediaIds = mediaIds != null && !mediaIds.isEmpty();
        boolean hasMediaInfo = mediaInfo != null && !mediaInfo.trim().isEmpty();
        if (!(hasText || hasMediaIds || hasMediaInfo)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "content 不能为空");
        }
    }

    /**
     * 内容锁：一旦设置了“待执行”的定时发布，就禁止再改内容（草稿/发布）。
     *
     * <p>解锁方式：先取消定时任务（status!=0 或 is_canceled=1）。</p>
     */
    private void assertNotScheduled(Long postId) {
        if (postId == null) {
            return;
        }
        ContentScheduleEntity active = contentRepository.findActiveScheduleByPostId(postId);
        if (active != null) {
            throw new AppException(ResponseCode.CONFLICT.getCode(), "已设置定时发布，如需编辑请先取消定时任务");
        }
    }

    /**
     * 软删内容：使用与 publish 相同的 postId 锁，并用状态/版本条件更新，避免并发下“删了又活”。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO delete(Long userId, Long postId) {
        if (postId == null) {
            return OperationResultVO.builder()
                    .success(false)
                    .status("ILLEGAL_PARAMETER")
                    .message("postId 不能为空")
                    .build();
        }

        RLock lock = lockFor(postId);
        lock.lock();
        try {
            ContentPostEntity post = contentRepository.findPostForUpdate(postId);
            if (post == null) {
                return OperationResultVO.builder()
                        .success(false)
                        .id(postId)
                        .status("NOT_FOUND")
                        .message("未找到或无权限")
                        .build();
            }
            if (userId == null || post.getUserId() == null || !post.getUserId().equals(userId)) {
                return OperationResultVO.builder()
                        .success(false)
                        .id(postId)
                        .status("NO_PERMISSION")
                        .message("未找到或无权限")
                        .build();
            }
            if (post.getStatus() != null && post.getStatus() == STATUS_DELETED) {
                // 幂等：已删除就直接返回，不重复派发事件。
                return OperationResultVO.builder()
                        .success(true)
                        .id(postId)
                        .status("DELETED")
                        .message("已删除")
                        .build();
            }

            boolean ok = contentRepository.softDeleteIfMatchStatusAndVersion(
                    postId,
                    post.getStatus(),
                    post.getVersionNum(),
                    socialIdPort.now());
            if (ok) {
                dispatchDeleteAfterCommit(postId, userId, post.getVersionNum());
            }
            return OperationResultVO.builder()
                    .success(ok)
                    .id(postId)
                    .status(ok ? "DELETED" : "VERSION_MISMATCH")
                    .message(ok ? "已删除" : "删除失败（版本不匹配）")
                    .build();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 定时发布创建：校验 userId，使用内容+时间生成幂等 token，若已有未执行任务直接返回；否则创建任务并处理并发主键冲突，返回任务状态。
     */
    @Override
    public OperationResultVO schedule(Long userId, Long postId, Long publishTime, String timezone) {
        if (userId == null) {
            return OperationResultVO.builder()
                    .success(false)
                    .status("USER_REQUIRED")
                    .message("userId 不能为空")
                    .build();
        }
        if (postId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "请先创建草稿拿到 postId");
        }
        ContentDraftEntity draft = contentRepository.findDraft(postId);
        if (draft == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        if (draft.getUserId() != null && !draft.getUserId().equals(userId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "NO_PERMISSION");
        }
        validateContentNotEmpty(draft.getDraftContent(), null, draft.getMediaIds());
        String token = hash(userId + ":" + postId + ":" + publishTime);
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
                    .postId(postId)
                    .contentData(draft.getDraftContent())
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
     * 客户端草稿同步：仅允许 owner 覆盖更新；草稿不存在直接返回 NOT_FOUND（避免被撞库创建垃圾草稿）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DraftSyncVO syncDraft(Long draftId, Long userId, String diffContent, Long clientVersion, String deviceId, List<String> mediaIds) {
        assertNotScheduled(draftId);
        validateContentNotEmpty(diffContent, mediaIds, null);
        ContentDraftEntity entity = contentRepository.findDraftForUpdate(draftId);
        if (entity == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        if (entity.getUserId() != null && userId != null && !entity.getUserId().equals(userId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "NO_PERMISSION");
        }
        if (!isNewerClientVersion(clientVersion, entity.getClientVersion())) {
            throw new AppException(ResponseCode.CONFLICT.getCode(), "STALE_VERSION");
        }
        entity.setDraftContent(diffContent);
        entity.setDeviceId(deviceId);
        entity.setClientVersion(clientVersion);
        if (mediaIds != null) {
            entity.setMediaIds(String.join(",", mediaIds));
        }
        entity.setUpdateTime(socialIdPort.now());
        contentRepository.saveDraft(entity);
        return DraftSyncVO.builder()
                .serverVersion(entity.getClientVersion())
                .syncTime(entity.getUpdateTime())
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
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO processSchedules(Long now, Integer limit) {
        long ts = now == null ? socialIdPort.now() : now;
        List<ContentScheduleEntity> tasks = contentRepository.listPendingSchedules(ts, limit == null ? 50 : limit);
        int success = 0;
        for (ContentScheduleEntity task : tasks) {
            if (task.getIsCanceled() != null && task.getIsCanceled() == 1) {
                contentRepository.updateScheduleStatus(task.getTaskId(), SCHEDULE_STATUS_CANCELED, task.getRetryCount(), "已取消跳过", 0, null, SCHEDULE_STATUS_SCHEDULED);
                continue;
            }
            ContentDraftEntity draft = contentRepository.findDraft(task.getPostId());
            if (draft == null) {
                contentRepository.updateScheduleStatus(task.getTaskId(),
                        SCHEDULE_STATUS_CANCELED,
                        task.getRetryCount(),
                        "draft_not_found",
                        1,
                        null,
                        SCHEDULE_STATUS_SCHEDULED);
                log.error("schedule task skipped due to draft not found, taskId={}, postId={}", task.getTaskId(), task.getPostId());
                continue;
            }
            OperationResultVO res = publishInternal(task.getPostId(), task.getUserId(), draft.getDraftContent(), draft.getMediaIds(), null, "PUBLIC", null);
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
    @Transactional(rollbackFor = Exception.class)
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
        ContentDraftEntity draft = contentRepository.findDraft(task.getPostId());
        if (draft == null) {
            contentRepository.updateScheduleStatus(taskId, SCHEDULE_STATUS_CANCELED, task.getRetryCount(), "draft_not_found", 1, null, SCHEDULE_STATUS_SCHEDULED);
            return OperationResultVO.builder().success(false).id(taskId).status("DRAFT_NOT_FOUND").message("草稿不存在，已终止定时任务").build();
        }
        OperationResultVO res = publishInternal(task.getPostId(), task.getUserId(), draft.getDraftContent(), draft.getMediaIds(), null, "PUBLIC", null);
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
        // 定时任务一旦创建，就不允许再改内容；想改必须先取消定时任务。
        if (contentData != null && !contentData.trim().isEmpty()) {
            throw new AppException(ResponseCode.CONFLICT.getCode(), "已设置定时发布，如需编辑请先取消定时任务");
        }
        Long postId = task.getPostId();
        String token = hash(userId + ":" + (postId == null ? "0" : postId) + ":" + publishTime);
        boolean ok = contentRepository.updateSchedule(taskId, userId, publishTime, task.getContentData(), token, reason);
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO applyRiskReviewResult(Long attemptId, String finalResult, String reasonCode) {
        if (attemptId == null) {
            return OperationResultVO.builder().success(false).status("ILLEGAL_PARAMETER").message("attemptId 不能为空").build();
        }
        ContentPublishAttemptEntity attempt = contentPublishAttemptRepository.findByAttemptId(attemptId);
        if (attempt == null) {
            return OperationResultVO.builder().success(false).status("NOT_FOUND").message("发布尝试不存在").build();
        }
        if (attempt.getAttemptStatus() == null || attempt.getAttemptStatus() != ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode()) {
            return toPublishResultFromAttempt(attempt);
        }
        if (finalResult == null || finalResult.isBlank()) {
            return toPublishResultFromAttempt(attempt);
        }

        Long postId = attempt.getPostId();
        Long userId = attempt.getUserId();
        if (postId == null || userId == null) {
            return OperationResultVO.builder().success(false).status("ILLEGAL_STATE").message("attempt 缺少 postId/userId").build();
        }

        if ("PASS".equalsIgnoreCase(finalResult)) {
            Integer expectedVersion = attempt.getPublishedVersionNum();
            boolean okPost = contentRepository.updatePostStatusIfMatchVersion(postId, STATUS_PUBLISHED, STATUS_PENDING_REVIEW, expectedVersion);
            if (!okPost) {
                log.warn("risk review apply skipped (post status/version not match), postId={}, expectedVersion={}, attemptId={}", postId, expectedVersion, attemptId);
                ContentPublishAttemptEntity latest = contentPublishAttemptRepository.findByAttemptId(attemptId);
                return toPublishResultFromAttempt(latest == null ? attempt : latest);
            }
            boolean okAttempt = contentPublishAttemptRepository.updateAttemptStatus(
                    attemptId,
                    ContentPublishAttemptStatusEnumVO.PUBLISHED.getCode(),
                    ContentPublishAttemptRiskStatusEnumVO.PASSED.getCode(),
                    ContentPublishAttemptTranscodeStatusEnumVO.DONE.getCode(),
                    attempt.getTranscodeJobId(),
                    attempt.getPublishedVersionNum(),
                    null,
                    null,
                    ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode());
            if (!okAttempt) {
                throw new IllegalStateException("Attempt 状态推进失败 attemptId=" + attemptId);
            }
            dispatchAfterCommit(postId, userId, attempt.getPublishedVersionNum());
            attempt.setAttemptStatus(ContentPublishAttemptStatusEnumVO.PUBLISHED.getCode());
            attempt.setRiskStatus(ContentPublishAttemptRiskStatusEnumVO.PASSED.getCode());
            attempt.setTranscodeStatus(ContentPublishAttemptTranscodeStatusEnumVO.DONE.getCode());
            return toPublishResultFromAttempt(attempt);
        }

        if ("BLOCK".equalsIgnoreCase(finalResult)) {
            Integer expectedVersion = attempt.getPublishedVersionNum();
            boolean okPost = contentRepository.updatePostStatusIfMatchVersion(postId, STATUS_REJECTED, STATUS_PENDING_REVIEW, expectedVersion);
            if (!okPost) {
                log.warn("risk review apply skipped (post status/version not match), postId={}, expectedVersion={}, attemptId={}", postId, expectedVersion, attemptId);
                ContentPublishAttemptEntity latest = contentPublishAttemptRepository.findByAttemptId(attemptId);
                return toPublishResultFromAttempt(latest == null ? attempt : latest);
            }
            boolean okAttempt = contentPublishAttemptRepository.updateAttemptStatus(
                    attemptId,
                    ContentPublishAttemptStatusEnumVO.RISK_REJECTED.getCode(),
                    ContentPublishAttemptRiskStatusEnumVO.REJECTED.getCode(),
                    ContentPublishAttemptTranscodeStatusEnumVO.NOT_STARTED.getCode(),
                    attempt.getTranscodeJobId(),
                    attempt.getPublishedVersionNum(),
                    reasonCode == null ? "RISK_REJECTED" : reasonCode,
                    "风控拦截",
                    ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode());
            if (!okAttempt) {
                throw new IllegalStateException("Attempt 状态推进失败 attemptId=" + attemptId);
            }
            attempt.setAttemptStatus(ContentPublishAttemptStatusEnumVO.RISK_REJECTED.getCode());
            attempt.setRiskStatus(ContentPublishAttemptRiskStatusEnumVO.REJECTED.getCode());
            attempt.setErrorCode(reasonCode == null ? "RISK_REJECTED" : reasonCode);
            attempt.setErrorMessage("风控拦截");
            return toPublishResultFromAttempt(attempt);
        }

        return toPublishResultFromAttempt(attempt);
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

    private boolean isNewerClientVersion(Long incoming, Long current) {
        if (incoming == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "clientVersion 不能为空");
        }
        long cur = current == null ? 0L : current;
        return incoming >= cur;
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

    private void dispatchAfterCommit(Long postId, Long userId, Integer versionNum) {
        if (postId == null || userId == null) {
            return;
        }
        // 事务内先落 outbox：即使 MQ 不可用，也能保证“写库成功后事件不丢”。
        long tsMs = socialIdPort.now();
        contentEventOutboxPort.savePostPublished(postId, userId, versionNum, tsMs);
        contentEventOutboxPort.savePostSummaryGenerate(postId, userId, versionNum, tsMs);

        // 防御：若未来有人把 publish() 改成“事务外调用”，至少还能尝试投递。
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            contentEventOutboxPort.tryPublishPending();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    contentEventOutboxPort.tryPublishPending();
                } catch (Exception e) {
                    log.error("content outbox tryPublishPending failed after commit, postId={}, userId={}", postId, userId, e);
                }
            }
        });
    }

    private void dispatchUpdateAfterCommit(Long postId, Long operatorId, Integer versionNum) {
        if (postId == null || operatorId == null) {
            return;
        }
        long tsMs = socialIdPort.now();
        contentEventOutboxPort.savePostUpdated(postId, operatorId, versionNum, tsMs);
        contentEventOutboxPort.savePostSummaryGenerate(postId, operatorId, versionNum, tsMs);

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            contentEventOutboxPort.tryPublishPending();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    contentEventOutboxPort.tryPublishPending();
                } catch (Exception e) {
                    log.error("content outbox tryPublishPending failed after commit, postId={}, operatorId={}", postId, operatorId, e);
                }
            }
        });
    }

    private void dispatchDeleteAfterCommit(Long postId, Long operatorId, Integer versionNum) {
        if (postId == null || operatorId == null) {
            return;
        }
        contentEventOutboxPort.savePostDeleted(postId, operatorId, versionNum, socialIdPort.now());

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            contentEventOutboxPort.tryPublishPending();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    contentEventOutboxPort.tryPublishPending();
                } catch (Exception e) {
                    log.error("content outbox tryPublishPending failed after commit, postId={}, operatorId={}", postId, operatorId, e);
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
        if (attemptStatus != null && attemptStatus == ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode()) {
            return OperationResultVO.builder()
                    .success(false)
                    .id(attempt.getPostId())
                    .attemptId(attempt.getAttemptId())
                    .versionNum(attempt.getPublishedVersionNum())
                    .status("PENDING_REVIEW")
                    .message(attempt.getErrorMessage() == null ? "内容审核中" : attempt.getErrorMessage())
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
