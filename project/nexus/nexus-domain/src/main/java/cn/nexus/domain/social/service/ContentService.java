package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IContentEventOutboxPort;
import cn.nexus.domain.social.adapter.port.IContentCacheEvictPort;
import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IMediaTranscodePort;
import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IContentPublishAttemptRepository;
import cn.nexus.domain.social.adapter.repository.IRelationEventOutboxRepository;
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
import java.util.UUID;

/**
 * 内容生产与发布领域服务实现。
 *
 * @author rr
 * @author rr
 * @author codex
 * @author codex
 * @since 2025-12-26
 */
@Service
@RequiredArgsConstructor
public class ContentService implements IContentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContentService.class);

    private final ISocialIdPort socialIdPort;
    private final IContentRepository contentRepository;
    private final IPostContentKvPort postContentKvPort;
    private final IContentPublishAttemptRepository contentPublishAttemptRepository;
    private final IMediaStoragePort mediaStoragePort;
    private final IMediaTranscodePort mediaTranscodePort;
    private final IContentEventOutboxPort contentEventOutboxPort;
    private final IRelationEventOutboxRepository relationEventOutboxRepository;
    private final IContentCacheEvictPort contentCacheEvictPort;
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
     *
     * @param fileType 文件类型（MIME，可为空） {@link String}
     * @param fileSize 文件大小（字节，可为空） {@link Long}
     * @param crc32 内容 CRC32（可为空，当前不强校验） {@link String}
     * @return 上传会话信息 {@link UploadSessionVO}
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
     * 草稿保存/覆盖更新：draftId 为空则创建新草稿，否则覆盖更新同一条草稿（ {@code draftId = postId}）。
     *
     * @param userId 用户 ID {@link Long}
     * @param draftId 草稿 ID（可为空，空则创建） {@link Long}
     * @param title 标题（可为空） {@link String}
     * @param contentText 正文（可为空，但需与媒体至少一个非空） {@link String}
     * @param mediaIds 媒体 ID 列表（可为空） {@link List}
     * @return 草稿保存结果 {@link DraftVO}
     */
    @Override
    public DraftVO saveDraft(Long userId, Long draftId, String title, String contentText, List<String> mediaIds) {
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
                .title(normalizeTitle(title))
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
     * 1. 获取锁并校验作者权限；
     * 2. 创建发布 Attempt（含幂等 token 与输入快照）；
     * 3. 风控失败或转码未就绪：仅更新 Attempt，不更新 {@code content_post}，也不写 {@code content_history}；
     * 4. 仅当成功发布：写入 {@code content_history} + 更新 {@code content_post} + 分发事件，同时 Attempt -&gt; PUBLISHED。
     *
     * @param postId 帖子 ID {@link Long}
     * @param userId 用户 ID {@link Long}
     * @param title 标题（可为空，会做兜底） {@link String}
     * @param text 正文（可为空，但需与媒体至少一个非空） {@link String}
     * @param mediaInfo 媒体信息（可为空） {@link String}
     * @param location 位置信息（可为空） {@link String}
     * @param visibility 可见性（可为空） {@link String}
     * @param postTypes 帖子类型列表（可为空） {@link List}
     * @return 发布结果 {@link OperationResultVO}
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public OperationResultVO publish(Long postId, Long userId, String title, String text, String mediaInfo, String location, String visibility, List<String> postTypes) {
        assertNotScheduled(postId);
        return publishInternal(postId, userId, title, text, mediaInfo, location, visibility, postTypes);
    }

    private OperationResultVO publishInternal(Long postId, Long userId, String title, String text, String mediaInfo, String location, String visibility, List<String> postTypes) {
        assertTx("publish");
        validateContentNotEmpty(text, null, mediaInfo);
        String normalizedTitle = requirePublishTitle(title);
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
                    normalizedTitle + ":" +
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
                    .snapshotTitle(normalizedTitle)
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

                upsertPost(targetPostId, userId, normalizedTitle, text, mediaInfo, location, visibility, STATUS_PENDING_REVIEW, newVersion, existedPost != null);
                contentRepository.saveHistory(ContentHistoryEntity.builder()
                        .historyId(socialIdPort.nextId())
                        .postId(targetPostId)
                        .versionNum(newVersion)
                        .snapshotTitle(normalizedTitle)
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

            upsertPost(targetPostId, userId, normalizedTitle, text, mediaInfo, location, visibility, STATUS_PUBLISHED, newVersion, false);
            contentRepository.saveHistory(ContentHistoryEntity.builder()
                    .historyId(socialIdPort.nextId())
                    .postId(targetPostId)
                    .versionNum(newVersion)
                    .snapshotTitle(normalizedTitle)
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

            if (!wasPublished(existedPost)) {
                savePostCounterOutbox(targetPostId, userId, "PUBLISHED");
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
     *
     * @param userId 用户 ID {@link Long}
     * @param postId 帖子 ID {@link Long}
     * @return 删除结果 {@link OperationResultVO}
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
                if (wasPublished(post)) {
                    savePostCounterOutbox(postId, userId, "UNPUBLISHED");
                }
                if (post.getContentUuid() != null && !post.getContentUuid().isBlank()) {
                    postContentKvPort.delete(post.getContentUuid());
                }
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
     *
     * @param userId 用户 ID {@link Long}
     * @param postId 帖子 ID {@link Long}
     * @param publishTime 计划发布时间（毫秒时间戳，可为空） {@link Long}
     * @param timezone 时区（当前未使用，透传保留） {@link String}
     * @return 定时发布任务创建结果 {@link OperationResultVO}
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
        requirePublishTitle(draft.getTitle());
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
     *
     * @param draftId 草稿 ID {@link Long}
     * @param userId 用户 ID {@link Long}
     * @param title 标题（可为空） {@link String}
     * @param diffContent 草稿内容（当前实现为整段覆盖，不是真 diff） {@link String}
     * @param clientVersion 客户端版本号 {@link Long}
     * @param deviceId 设备 ID（可为空） {@link String}
     * @param mediaIds 媒体 ID 列表（可为空） {@link List}
     * @return 同步结果 {@link DraftSyncVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DraftSyncVO syncDraft(Long draftId, Long userId, String title, String diffContent, Long clientVersion, String deviceId, List<String> mediaIds) {
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
        entity.setTitle(normalizeTitle(title));
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
     *
     * @param postId 帖子 ID {@link Long}
     * @param userId 用户 ID {@link Long}
     * @param limit 分页大小（可为空） {@link Integer}
     * @param offset 分页偏移（可为空） {@link Integer}
     * @return 历史版本查询结果 {@link ContentHistoryVO}
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
                    .title(history.getSnapshotTitle())
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

    /**
     * 内容回滚：在事务与行锁下校验权限，定位目标版本快照并生成新版本，更新帖子状态与历史记录。
     *
     * @param postId 帖子 ID {@link Long}
     * @param userId 用户 ID {@link Long}
     * @param targetVersionId 目标版本号 {@link Long}
     * @return 回滚结果 {@link OperationResultVO}
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
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

            String newContentUuid = UUID.randomUUID().toString();
            postContentKvPort.add(newContentUuid, content == null ? "" : content);
            String oldUuid = post == null ? null : post.getContentUuid();

            boolean ok = contentRepository.updatePostStatusAndContent(
                    postId,
                    STATUS_PUBLISHED,
                    newVersion,
                    true,
                    target.getSnapshotTitle(),
                    post == null ? null : post.getPublishTime(),
                    newContentUuid,
                    post == null ? null : post.getMediaInfo(),
                    post == null ? null : post.getLocationInfo(),
                    post == null ? 0 : post.getVisibility());

            if (ok && oldUuid != null && !oldUuid.isBlank() && !oldUuid.equals(newContentUuid)) {
                postContentKvPort.delete(oldUuid);
            }
            contentRepository.saveHistory(cn.nexus.domain.social.model.entity.ContentHistoryEntity.builder()
                    .historyId(socialIdPort.nextId())
                    .postId(postId)
                    .versionNum(newVersion)
                    .snapshotTitle(target.getSnapshotTitle())
                    .snapshotContent(content)
                    .snapshotMedia(post == null ? null : post.getMediaInfo())
                    .createTime(socialIdPort.now())
                    .build());
            if (ok) {
                if (!wasPublished(post)) {
                    savePostCounterOutbox(postId, userId, "PUBLISHED");
                }
                dispatchUpdateAfterCommit(postId, userId, newVersion);
            }
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
     * 批量执行到期定时任务：按时间查询待处理任务，跳过已取消，逐条调用 publish 并按结果更新状态与重试计数。
     *
     * <p>重试采用退避策略，避免短时间内重复失败打爆下游；超过重试上限后终止任务并打报警标记。</p>
     *
     * @param now 当前时间戳（毫秒，传空则使用系统时间） {@link Long}
     * @param limit 本次拉取的最大任务数（传空则使用默认值） {@link Integer}
     * @return 执行统计结果 {@link OperationResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO processSchedules(Long now, Integer limit) {
        // now 允许外部透传，便于“补偿/回放”场景；传空则使用系统当前时间。
        long ts = now == null ? socialIdPort.now() : now;
        List<ContentScheduleEntity> tasks = contentRepository.listPendingSchedules(ts, limit == null ? 50 : limit);
        int success = 0;
        for (ContentScheduleEntity task : tasks) {
            // 幂等与并发保护：updateScheduleStatus 内部使用 expectedStatus 做 CAS 更新，避免重复消费时相互覆盖。
            if (task.getIsCanceled() != null && task.getIsCanceled() == 1) {
                contentRepository.updateScheduleStatus(task.getTaskId(), SCHEDULE_STATUS_CANCELED, task.getRetryCount(), "已取消跳过", 0, null, SCHEDULE_STATUS_SCHEDULED);
                continue;
            }
            ContentDraftEntity draft = contentRepository.findDraft(task.getPostId());
            if (draft == null) {
                // 草稿缺失意味着用户已删除/清理草稿或数据异常：直接终止任务并打报警标记，避免无限重试。
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
            OperationResultVO res = publishInternal(task.getPostId(), task.getUserId(), draft.getTitle(), draft.getDraftContent(), draft.getMediaIds(), null, "PUBLIC", null);
            if (res.isSuccess()) {
                success++;
                contentRepository.updateScheduleStatus(task.getTaskId(), SCHEDULE_STATUS_PUBLISHED, task.getRetryCount(), null, 0, null, SCHEDULE_STATUS_SCHEDULED);
            } else {
                int currentRetry = task.getRetryCount() == null ? 0 : task.getRetryCount();
                int nextRetry = currentRetry + 1;
                boolean exceed = nextRetry >= MAX_RETRY;
                // 退避重试：减少瞬时失败时的重复提交，给下游“恢复窗口”。
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
     * 执行单个定时发布任务：校验状态与取消标记后调用 publish，并根据结果更新状态、重试次数与下一次执行时间。
     *
     * <p>当发布失败时采用退避重试；超过重试上限后终止任务并打报警标记。</p>
     *
     * @param taskId 定时任务 ID {@link Long}
     * @return 执行结果 {@link OperationResultVO}
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
            // 草稿被删除/不可见：终止任务并打报警标记，避免重复消费导致的“空转”。
            contentRepository.updateScheduleStatus(taskId, SCHEDULE_STATUS_CANCELED, task.getRetryCount(), "draft_not_found", 1, null, SCHEDULE_STATUS_SCHEDULED);
            return OperationResultVO.builder().success(false).id(taskId).status("DRAFT_NOT_FOUND").message("草稿不存在，已终止定时任务").build();
        }
        OperationResultVO res = publishInternal(task.getPostId(), task.getUserId(), draft.getTitle(), draft.getDraftContent(), draft.getMediaIds(), null, "PUBLIC", null);
        if (res.isSuccess()) {
            contentRepository.updateScheduleStatus(taskId, SCHEDULE_STATUS_PUBLISHED, task.getRetryCount(), null, 0, null, SCHEDULE_STATUS_SCHEDULED);
        } else {
            int currentRetry = task.getRetryCount() == null ? 0 : task.getRetryCount();
            int nextRetry = currentRetry + 1;
            boolean exceed = nextRetry >= MAX_RETRY;
            // 退避重试：避免短时间内重复失败打爆数据库/下游。
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
     * 取消定时发布任务：仅允许任务拥有者取消，取消后不再触发发布。
     *
     * @param taskId 定时任务 ID {@link Long}
     * @param userId 操作用户 ID {@link Long}
     * @param reason 取消原因（可为空） {@link String}
     * @return 取消结果 {@link OperationResultVO}
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
     * 更新定时发布任务：允许调整发布时间与备注；不允许修改任务绑定的内容快照。
     *
     * <p>不允许修改 contentData 的原因：定时发布与草稿编辑是两条链路。想改内容必须先取消任务再重新创建，避免发布旧内容造成误解。</p>
     *
     * @param taskId 定时任务 ID {@link Long}
     * @param userId 操作用户 ID {@link Long}
     * @param publishTime 新的计划发布时间（毫秒时间戳，可为空） {@link Long}
     * @param contentData 任务内容快照（兼容字段，当前必须为空） {@link String}
     * @param reason 变更原因/备注（可为空） {@link String}
     * @return 更新结果 {@link OperationResultVO}
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
     * 获取定时任务审计信息：仅允许任务拥有者查看。
     *
     * @param taskId 定时任务 ID {@link Long}
     * @param userId 操作用户 ID {@link Long}
     * @return 任务信息，不存在或无权限返回 null {@link ContentScheduleEntity}
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

    /**
     * 获取发布 Attempt 的审计信息：仅允许 Attempt 拥有者查看，避免泄露内容快照等敏感信息。
     *
     * @param attemptId 发布 Attempt ID {@link Long}
     * @param userId 操作用户 ID {@link Long}
     * @return Attempt 信息，不存在或无权限返回 null {@link ContentPublishAttemptEntity}
     */
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
     * 风控审核结果回流：把处于 PENDING_REVIEW 的发布 Attempt 推进到最终状态（PASS/BLOCK），并同步推进帖子状态。
     *
     * <p>关键约束：</p>
     * <p>1. 只处理 PENDING_REVIEW 的 Attempt，其它状态直接返回；</p>
     * <p>2. 使用版本号/期望状态做 CAS 更新，避免旧 Attempt 覆盖新版本；</p>
     * <p>3. 缓存失效与事件投递放到事务提交后执行，避免回滚导致外部可见不一致。</p>
     *
     * @param attemptId 发布 Attempt ID {@link Long}
     * @param finalResult 最终结论（PASS/BLOCK） {@link String}
     * @param reasonCode 原因码（可为空） {@link String}
     * @return 推进后的结果 {@link OperationResultVO}
     */
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
        // 幂等：只处理“等待审核”的 Attempt；其它状态直接按当前 Attempt 映射返回。
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
            // 先推进 post：用“期望版本号 + 期望状态”做 CAS 更新，避免旧审核结果覆盖新版本发布。
            boolean okPost = contentRepository.updatePostStatusAndPublishTimeIfMatchVersion(
                    postId,
                    STATUS_PUBLISHED,
                    STATUS_PENDING_REVIEW,
                    expectedVersion,
                    socialIdPort.now());
            if (!okPost) {
                // 版本/状态不匹配时直接返回：大概率是用户又发了一次，当前审核结果已经“过期”。
                log.warn("risk review apply skipped (post status/version not match), postId={}, expectedVersion={}, attemptId={}", postId, expectedVersion, attemptId);
                ContentPublishAttemptEntity latest = contentPublishAttemptRepository.findByAttemptId(attemptId);
                return toPublishResultFromAttempt(latest == null ? attempt : latest);
            }
            // 再推进 Attempt：同样使用 expectedStatus 做 CAS 更新，保证并发回流下只会成功一次。
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
            // 事务提交后再做缓存失效与 outbox 投递，确保“写库成功”先于“外部可见”。
            savePostCounterOutbox(postId, userId, "PUBLISHED");
            dispatchAfterCommit(postId, userId, attempt.getPublishedVersionNum());
            attempt.setAttemptStatus(ContentPublishAttemptStatusEnumVO.PUBLISHED.getCode());
            attempt.setRiskStatus(ContentPublishAttemptRiskStatusEnumVO.PASSED.getCode());
            attempt.setTranscodeStatus(ContentPublishAttemptTranscodeStatusEnumVO.DONE.getCode());
            return toPublishResultFromAttempt(attempt);
        }

        if ("BLOCK".equalsIgnoreCase(finalResult)) {
            Integer expectedVersion = attempt.getPublishedVersionNum();
            // 被拦截：把 post 从“待审核”推进到“已拒绝”，同样做版本号匹配，避免覆盖新版本。
            boolean okPost = contentRepository.updatePostStatusIfMatchVersion(postId, STATUS_REJECTED, STATUS_PENDING_REVIEW, expectedVersion);
            if (!okPost) {
                log.warn("risk review apply skipped (post status/version not match), postId={}, expectedVersion={}, attemptId={}", postId, expectedVersion, attemptId);
                ContentPublishAttemptEntity latest = contentPublishAttemptRepository.findByAttemptId(attemptId);
                return toPublishResultFromAttempt(latest == null ? attempt : latest);
            }
            // Attempt 标记为风控拒绝：记录原因码，便于前端/运营侧展示与审计。
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

            long tsMs = socialIdPort.now();
            // 触发一次“内容更新”事件：让下游刷新展示（例如从“审核中”变成“被拦截”）。
            contentEventOutboxPort.savePostUpdated(postId, userId, attempt.getPublishedVersionNum(), tsMs);
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                try {
                    contentCacheEvictPort.evictPost(postId);
                } catch (Exception e) {
                    log.warn("evict content caches failed after BLOCK, postId={}", postId, e);
                }
                contentEventOutboxPort.tryPublishPending();
                return toPublishResultFromAttempt(attempt);
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 执行 afterCommit 逻辑。
                 *
                 */
                @Override
                public void afterCommit() {
                    // 事务提交后再做缓存失效与事件投递，避免回滚导致外部可见不一致。
                    try {
                        contentCacheEvictPort.evictPost(postId);
                        contentEventOutboxPort.tryPublishPending();
                    } catch (Exception e) {
                        log.error("evict/publish failed after BLOCK commit, postId={}", postId, e);
                    }
                }
            });
            return toPublishResultFromAttempt(attempt);
        }

        return toPublishResultFromAttempt(attempt);
    }

    /**
     * 触发存储重平衡：当前实现采用历史版本全量快照，因此无需搬迁/重平衡，接口仅用于兼容调用方。
     *
     * @param postId 帖子 ID {@link Long}
     * @return 操作结果 {@link OperationResultVO}
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

    private String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String trimmed = title.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String requirePublishTitle(String title) {
        String normalized = normalizeTitle(title);
        if (normalized == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "title 不能为空");
        }
        return normalized;
    }

    private int parseVisibility(String visibility) {
        if (visibility == null) {
            return 0;
        }
        switch (visibility.toUpperCase()) {
            case "PRIVATE":
                return 2;
            case "FRIEND":
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "不支持 FRIEND 可见性");
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

    private void upsertPost(Long postId, Long userId, String title, String text, String mediaInfo, String location, String visibility, int status, int versionNum, boolean edited) {
        String newContentUuid = UUID.randomUUID().toString();
        postContentKvPort.add(newContentUuid, text == null ? "" : text);

        ContentPostEntity post = contentRepository.findPost(postId);
        String oldUuid = null;
        try {
            if (post == null) {
                post = ContentPostEntity.builder()
                        .postId(postId)
                        .userId(userId)
                        .title(title)
                        .contentUuid(newContentUuid)
                        .mediaType(deriveMediaType(mediaInfo))
                        .mediaInfo(mediaInfo)
                        .locationInfo(location)
                        .status(status)
                        .visibility(parseVisibility(visibility))
                        .versionNum(versionNum)
                        .edited(edited)
                        .createTime(socialIdPort.now())
                        .publishTime(status == STATUS_PUBLISHED ? socialIdPort.now() : null)
                        .build();
                contentRepository.savePost(post);
            } else {
                if (userId != null && post.getUserId() != null && !post.getUserId().equals(userId)) {
                    throw new IllegalStateException("post 所属用户不匹配");
                }
                oldUuid = post.getContentUuid();
                Long publishTime = post.getPublishTime();
                if (publishTime == null && status == STATUS_PUBLISHED) {
                    publishTime = socialIdPort.now();
                }
                boolean ok = contentRepository.updatePostStatusAndContent(postId, status, versionNum, edited, title, publishTime, newContentUuid, mediaInfo, location, parseVisibility(visibility));
                if (!ok) {
                    throw new IllegalStateException("post 更新失败，可能存在版本冲突 postId=" + postId);
                }
            }
        } catch (Exception e) {
            try {
                postContentKvPort.delete(newContentUuid);
            } catch (Exception ignored) {
            }
            throw e;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            if (oldUuid != null && !oldUuid.isBlank() && !oldUuid.equals(newContentUuid)) {
                postContentKvPort.delete(oldUuid);
            }
            return;
        }

        String oldUuidFinal = oldUuid;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 执行 afterCompletion 逻辑。
             *
             * @param status status 参数。类型：{@code int}
             */
            @Override
            public void afterCompletion(int status) {
                // 提交：删除旧 contentUuid 对应的 KV；回滚：删除本次写入的新 KV，避免脏数据残留。
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    if (oldUuidFinal != null && !oldUuidFinal.isBlank() && !oldUuidFinal.equals(newContentUuid)) {
                        try {
                            postContentKvPort.delete(oldUuidFinal);
                        } catch (Exception ignored) {
                        }
                    }
                    return;
                }
                try {
                    postContentKvPort.delete(newContentUuid);
                } catch (Exception ignored) {
                }
            }
        });
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
            try {
                contentCacheEvictPort.evictPost(postId);
            } catch (Exception e) {
                log.warn("evict content caches failed, postId={}", postId, e);
            }
            contentEventOutboxPort.tryPublishPending();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 执行 afterCommit 逻辑。
             *
             */
            @Override
            public void afterCommit() {
                // 事务提交后再做缓存失效与事件投递，避免回滚导致外部可见不一致。
                try {
                    contentCacheEvictPort.evictPost(postId);
                    contentEventOutboxPort.tryPublishPending();
                } catch (Exception e) {
                    log.error("content outbox tryPublishPending failed after commit, postId={}, userId={}", postId, userId, e);
                }
            }
        });
    }

    private boolean wasPublished(ContentPostEntity post) {
        return post != null && post.getStatus() != null && post.getStatus() == STATUS_PUBLISHED;
    }

    private void savePostCounterOutbox(Long postId, Long authorId, String status) {
        if (postId == null || authorId == null || status == null || status.isBlank()) {
            return;
        }
        long eventId = socialIdPort.nextId();
        relationEventOutboxRepository.save(eventId, "POST", buildPostCounterPayload(eventId, authorId, postId, status));
    }

    private String buildPostCounterPayload(Long eventId, Long authorId, Long postId, String status) {
        return "{"
                + "\"eventId\":" + eventId + ","
                + "\"sourceId\":" + authorId + ","
                + "\"targetId\":" + postId + ","
                + "\"status\":\"" + safe(status) + "\""
                + "}";
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void dispatchUpdateAfterCommit(Long postId, Long operatorId, Integer versionNum) {
        if (postId == null || operatorId == null) {
            return;
        }
        long tsMs = socialIdPort.now();
        contentEventOutboxPort.savePostUpdated(postId, operatorId, versionNum, tsMs);
        contentEventOutboxPort.savePostSummaryGenerate(postId, operatorId, versionNum, tsMs);

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                contentCacheEvictPort.evictPost(postId);
            } catch (Exception e) {
                log.warn("evict content caches failed, postId={}", postId, e);
            }
            contentEventOutboxPort.tryPublishPending();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 执行 afterCommit 逻辑。
             *
             */
            @Override
            public void afterCommit() {
                // 事务提交后再做缓存失效与事件投递，避免回滚导致外部可见不一致。
                try {
                    contentCacheEvictPort.evictPost(postId);
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
            try {
                contentCacheEvictPort.evictPost(postId);
            } catch (Exception e) {
                log.warn("evict content caches failed, postId={}", postId, e);
            }
            contentEventOutboxPort.tryPublishPending();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 执行 afterCommit 逻辑。
             *
             */
            @Override
            public void afterCommit() {
                // 事务提交后再做缓存失效与事件投递，避免回滚导致外部可见不一致。
                try {
                    contentCacheEvictPort.evictPost(postId);
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
