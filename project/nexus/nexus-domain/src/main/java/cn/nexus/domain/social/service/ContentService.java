package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IContentDispatchPort;
import cn.nexus.domain.social.adapter.port.IContentRiskPort;
import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IMediaTranscodePort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentDraftEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.ContentRevisionEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.*;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内容领域服务实现。
 */
@Service
@RequiredArgsConstructor
public class ContentService implements IContentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContentService.class);

    private final ISocialIdPort socialIdPort;
    private final IContentRepository contentRepository;
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

    // 基准存储策略
    private static final int BASE_INTERVAL = 20;
    private static final double PATCH_FULL_THRESHOLD = 0.5; // patch 大于 50% 时改存基准
    private static final int MAX_TEXT_LENGTH_FOR_PATCH = 10000; // 超大文本直接存基准
    private static final long MAX_UPLOAD_SIZE_BYTES = 50L * 1024 * 1024; // 50MB 限制
    private static final List<String> ALLOWED_UPLOAD_TYPES = Arrays.asList("image/jpeg", "image/png", "image/jpg", "video/mp4", "application/octet-stream");
    // 重建保护
    private static final int MAX_PATCH_HOPS = 200;

    private static final String POST_LOCK_KEY_PREFIX = "lock:content:post:";
    private static final String REBUILD_CACHE_LOCK_KEY = "lock:content:rebuild-cache";
    // 简易重建缓存：最近 500 个版本
    private static final int CACHE_SIZE = 500;
    private final Map<String, String> rebuildCache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    // 重试退避策略
    private static final int MAX_RETRY = 5;
    private static final long BACKOFF_BASE_MS = 5_000L;
    private static final long BACKOFF_MAX_MS = 5 * 60_000L;
    private static final double BACKOFF_JITTER_RATE = 0.2;
    private final java.util.concurrent.atomic.AtomicInteger rebuildFailureCount = new java.util.concurrent.atomic.AtomicInteger(0);

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
     * 内容发布主流程：
     * 1) 获取锁并校验作者权限，确定新版本号、上一个版本全文及请求幂等键；
     * 2) 风控扫描、媒体转码失败时落 REJECTED/PROCESSING 状态并记录基准快照；
     * 3) 根据文本差异与基准策略选择存基准或差分，持久化 chunk/patch + 修订记录 + 历史快照；
     * 4) upsert post 状态为已发布，更新缓存并通知分发；全过程要求在事务内，避免版本冲突。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public OperationResultVO publish(Long postId, Long userId, String text, String mediaInfo, String location, String visibility) {
        assertTx("publish");
        Long targetPostId = postId == null ? socialIdPort.nextId() : postId;
        RLock lock = lockFor(targetPostId);
        lock.lock();
        try {
            ContentPostEntity existedPost = contentRepository.findPostForUpdate(targetPostId);
            if (existedPost != null && userId != null && existedPost.getUserId() != null && !existedPost.getUserId().equals(userId)) {
                return OperationResultVO.builder().success(false).id(targetPostId).status("NO_PERMISSION").message("无权限").build();
            }
            // 取最新版本，确定新版本号
            ContentRevisionEntity latest = contentRepository.findLatestRevisionForUpdate(targetPostId);
            int fallbackVersion = existedPost == null || existedPost.getVersionNum() == null ? 0 : existedPost.getVersionNum();
            int currentVersion = latest == null ? fallbackVersion : latest.getVersionNum();
            int newVersion = currentVersion + 1;
            String prevContent = latest == null ? "" : rebuildContent(targetPostId, latest.getVersionNum());

            String requestId = hash(targetPostId + ":" + newVersion + ":" + (text == null ? "" : text));

            // 风控与转码
            boolean passRisk = contentRiskPort.scanText(text) && contentRiskPort.scanMedia(mediaInfo);
            if (!passRisk) {
                upsertPost(targetPostId, userId, text, mediaInfo, location, visibility, STATUS_REJECTED, newVersion, false);
                saveRevision(targetPostId, newVersion, newVersion, true, null, gzipAndHash(text), requestId);
                // 兼容双写旧历史
                contentRepository.saveHistory(cn.nexus.domain.social.model.entity.ContentHistoryEntity.builder()
                        .historyId(socialIdPort.nextId())
                        .postId(targetPostId)
                        .versionNum(newVersion)
                        .snapshotContent(text)
                        .snapshotMedia(mediaInfo)
                        .createTime(socialIdPort.now())
                        .build());
                return OperationResultVO.builder().success(false).id(targetPostId).status("REJECTED").message("风控拦截").build();
            }
            boolean mediaReady = mediaTranscodePort.transcode(mediaInfo);
            if (!mediaReady) {
                upsertPost(targetPostId, userId, text, mediaInfo, location, visibility, STATUS_PENDING_REVIEW, newVersion, false);
                saveRevision(targetPostId, newVersion, newVersion, true, null, gzipAndHash(text), requestId);
                contentRepository.saveHistory(cn.nexus.domain.social.model.entity.ContentHistoryEntity.builder()
                        .historyId(socialIdPort.nextId())
                        .postId(targetPostId)
                        .versionNum(newVersion)
                        .snapshotContent(text)
                        .snapshotMedia(mediaInfo)
                        .createTime(socialIdPort.now())
                        .build());
                return OperationResultVO.builder().success(false).id(targetPostId).status("PROCESSING").message("媒体处理中").build();
            }

            // 生成基准或 patch
            boolean forceBase = newVersion % BASE_INTERVAL == 1 || (text != null && text.length() > MAX_TEXT_LENGTH_FOR_PATCH);
            String diffText = diff(prevContent, text);
            byte[] patchBytes = gzip(diffText);
            byte[] baseBytes = gzip(text);
            boolean useBase = forceBase || patchBytes.length > baseBytes.length * PATCH_FULL_THRESHOLD || prevContent.isEmpty();

            if (useBase) {
                String chunkHash = hash(baseBytes);
                contentRepository.saveChunk(chunkHash, baseBytes, baseBytes.length, "gzip");
                saveRevision(targetPostId, newVersion, newVersion, true, null, chunkHash, requestId);
            } else {
                ContentRevisionEntity base = findNearestBase(targetPostId, latest);
                int baseVersion = base == null ? currentVersion : base.getVersionNum();
                String patchHash = hash(patchBytes);
                contentRepository.savePatch(patchHash, patchBytes, patchBytes.length, "gzip");
                saveRevision(targetPostId, newVersion, baseVersion, false, patchHash, base == null ? null : base.getChunkHash(), requestId);
            }

            upsertPost(targetPostId, userId, text, mediaInfo, location, visibility, STATUS_PUBLISHED, newVersion, false);
            contentRepository.saveHistory(cn.nexus.domain.social.model.entity.ContentHistoryEntity.builder()
                    .historyId(socialIdPort.nextId())
                    .postId(targetPostId)
                    .versionNum(newVersion)
                    .snapshotContent(text)
                    .snapshotMedia(mediaInfo)
                    .createTime(socialIdPort.now())
                    .build());
            cachePut(targetPostId, newVersion, text);
            contentDispatchPort.onPublished(targetPostId, userId);
            return OperationResultVO.builder().success(true).id(targetPostId).status("PUBLISHED").message("发布成功").build();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 软删内容：按 userId 校验后设置删除状态，返回是否删除成功及状态文案。
     */
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
     * 内容历史查询：校验访问权限，分页拉取修订记录，必要时从旧历史迁移；逐版本重建正文生成 VO，输出下一游标及异常状态。
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
        List<ContentRevisionEntity> revisions = contentRepository.listRevisions(postId, pageSize + 1, cursor);
        if (revisions.isEmpty()) {
            migrateLegacyHistory(postId);
            revisions = contentRepository.listRevisions(postId, pageSize + 1, cursor);
        }
        revisions.sort(Comparator.comparing(ContentRevisionEntity::getVersionNum));
        List<ContentHistoryVO.ContentVersionVO> versions = new ArrayList<>();
        boolean hasMore = revisions.size() > pageSize;
        List<ContentRevisionEntity> page = revisions.size() > pageSize ? revisions.subList(0, pageSize) : revisions;
        try {
            for (ContentRevisionEntity rev : page) {
                String content = rebuildContent(postId, rev.getVersionNum());
                versions.add(ContentHistoryVO.ContentVersionVO.builder()
                        .versionId(rev.getVersionNum().longValue())
                        .content(content)
                        .time(rev.getCreateTime())
                        .build());
            }
        } catch (IllegalStateException e) {
            log.error("history rebuild failed postId=" + postId + ", offset=" + cursor + ", err=" + e.getMessage(), e);
            return ContentHistoryVO.builder().versions(Collections.emptyList()).nextCursor(null).status("REBUILD_FAILED").build();
        }
        long cost = System.currentTimeMillis() - start;
        if (cost > 1500L) {
            log.warn("history rebuild slow postId=" + postId + ", cost=" + cost + "ms, limit=" + pageSize);
        }
        Integer nextCursor = hasMore ? cursor + pageSize : null;
        return ContentHistoryVO.builder().versions(versions).nextCursor(nextCursor).build();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    /**
     * 内容回滚：在事务与行锁下校验权限，定位目标版本并重建正文，按基准策略生成新版本（可能存基准或差分），更新帖子状态与历史并写入缓存。
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
            ContentRevisionEntity target = contentRepository.findRevision(postId, targetVersionId == null ? null : targetVersionId.intValue());
            if (target == null) {
                return OperationResultVO.builder()
                        .success(false)
                        .id(postId)
                        .status("VERSION_NOT_FOUND")
                        .message("目标版本不存在")
                        .build();
            }
            String content = rebuildContent(postId, target.getVersionNum());
            int currentVersion = post == null || post.getVersionNum() == null ? 0 : post.getVersionNum();
            int newVersion = currentVersion + 1;

            // 回滚写入新版本（按基准策略）
            boolean forceBase = newVersion % BASE_INTERVAL == 1;
            String prevContent = rebuildContent(postId, currentVersion);
            String diffText = diff(prevContent, content);
            byte[] patchBytes = gzip(diffText);
            byte[] baseBytes = gzip(content);
            boolean useBase = forceBase || patchBytes.length > baseBytes.length * PATCH_FULL_THRESHOLD || prevContent.isEmpty();

            String requestId = hash(postId + ":" + newVersion + ":rollback");
            if (useBase) {
                String chunkHash = hash(baseBytes);
                contentRepository.saveChunk(chunkHash, baseBytes, baseBytes.length, "gzip");
                saveRevision(postId, newVersion, newVersion, true, null, chunkHash, requestId);
            } else {
                ContentRevisionEntity base = findNearestBase(postId, contentRepository.findRevision(postId, currentVersion));
                int baseVersion = base == null ? currentVersion : base.getVersionNum();
                String patchHash = hash(patchBytes);
                contentRepository.savePatch(patchHash, patchBytes, patchBytes.length, "gzip");
                saveRevision(postId, newVersion, baseVersion, false, patchHash, base == null ? null : base.getChunkHash(), requestId);
            }

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
            cachePut(postId, newVersion, content);
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
            OperationResultVO res = publish(null, task.getUserId(), task.getContentData(), null, null, "PUBLIC");
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
        OperationResultVO res = publish(null, task.getUserId(), task.getContentData(), null, null, "PUBLIC");
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

    /**
     * 存储重平衡：若当前补丁链过长，基于最新内容生成一个新的基准修订，减少后续重建开销，并返回新版本号。
     */
    @Override
    public OperationResultVO rebalanceStorage(Long postId) {
        ContentRevisionEntity latest = contentRepository.findLatestRevision(postId);
        if (latest == null) {
            return OperationResultVO.builder().success(false).status("EMPTY").message("无版本可重平衡").build();
        }
        int hops = countPatchChain(postId, latest.getVersionNum());
        if (hops <= BASE_INTERVAL) {
            return OperationResultVO.builder().success(true).status("SKIP").message("链路较短，无需重平衡").build();
        }
        String content = rebuildContent(postId, latest.getVersionNum());
        int newVersion = latest.getVersionNum() + 1;
        byte[] baseBytes = gzip(content);
        String chunkHash = hash(baseBytes);
        contentRepository.saveChunk(chunkHash, baseBytes, baseBytes.length, "gzip");
        saveRevision(postId, newVersion, newVersion, true, null, chunkHash, hash(postId + ":" + newVersion + ":rebalance"));
        log.info("rebalance created new base version postId={}, newVersion={}, oldChain={}", postId, newVersion, hops);
        return OperationResultVO.builder().success(true).status("REBALANCED").id((long) newVersion).message("新基准版本已创建").build();
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

    private void saveRevision(Long postId, Integer versionNum, Integer baseVersion, boolean isBase, String patchHash, String chunkHash, String requestId) {
        ContentRevisionEntity existed = contentRepository.findRevision(postId, versionNum);
        if (existed != null) {
            if (requestId != null && requestId.equals(existed.getRequestId())) {
                // 幂等重复提交直接返回
                return;
            }
            throw new IllegalStateException("重复版本写入，postId=" + postId + ", version=" + versionNum);
        }
        try {
            contentRepository.saveRevision(postId, versionNum, baseVersion, isBase, patchHash, chunkHash, requestId);
        } catch (DuplicateKeyException e) {
            ContentRevisionEntity again = contentRepository.findRevision(postId, versionNum);
            if (again != null && requestId != null && requestId.equals(again.getRequestId())) {
                return;
            }
            throw new IllegalStateException("并发版本冲突，postId=" + postId + ", version=" + versionNum, e);
        }
    }

    private void migrateLegacyHistory(Long postId) {
        List<cn.nexus.domain.social.model.entity.ContentHistoryEntity> legacy = contentRepository.listHistory(postId, null);
        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        legacy.sort(Comparator.comparing(cn.nexus.domain.social.model.entity.ContentHistoryEntity::getVersionNum));
        String prev = "";
        for (cn.nexus.domain.social.model.entity.ContentHistoryEntity h : legacy) {
            int ver = h.getVersionNum() == null ? 1 : h.getVersionNum();
            String content = h.getSnapshotContent() == null ? "" : h.getSnapshotContent();
            boolean forceBase = ver % BASE_INTERVAL == 1 || content.length() > MAX_TEXT_LENGTH_FOR_PATCH;
            String diffText = diff(prev, content);
            byte[] patchBytes = gzip(diffText);
            byte[] baseBytes = gzip(content);
            boolean useBase = forceBase || patchBytes.length > baseBytes.length * PATCH_FULL_THRESHOLD || prev.isEmpty();
            if (useBase) {
                String chunkHash = hash(baseBytes);
                contentRepository.saveChunk(chunkHash, baseBytes, baseBytes.length, "gzip");
                saveRevision(postId, ver, ver, true, null, chunkHash, hash(postId + ":" + ver + ":legacy"));
            } else {
                // 基于上一个基准
                ContentRevisionEntity base = findNearestBase(postId, contentRepository.findLatestRevision(postId));
                int baseVersion = base == null ? ver - 1 : base.getVersionNum();
                String patchHash = hash(patchBytes);
                contentRepository.savePatch(patchHash, patchBytes, patchBytes.length, "gzip");
                saveRevision(postId, ver, baseVersion, false, patchHash, base == null ? null : base.getChunkHash(), hash(postId + ":" + ver + ":legacy"));
            }
            cachePut(postId, ver, content);
            prev = content;
        }
    }

    private ContentRevisionEntity findNearestBase(Long postId, ContentRevisionEntity from) {
        if (from == null) {
            return null;
        }
        if (Boolean.TRUE.equals(from.getIsBase())) {
            return from;
        }
        ContentRevisionEntity cursor = from;
        while (cursor != null && !Boolean.TRUE.equals(cursor.getIsBase())) {
            cursor = contentRepository.findRevision(postId, cursor.getBaseVersion());
        }
        return cursor;
    }

    private String rebuildContent(Long postId, Integer targetVersion) {
        if (targetVersion == null || targetVersion <= 0) {
            return "";
        }
        String cacheKey = cacheKey(postId, targetVersion);
        RLock cacheLock = rebuildCacheLock();
        cacheLock.lock();
        try {
            if (rebuildCache.containsKey(cacheKey)) {
                return rebuildCache.get(cacheKey);
            }
        } finally {
            cacheLock.unlock();
        }
        ContentRevisionEntity target = contentRepository.findRevision(postId, targetVersion);
        if (target == null) {
            log.error("rebuild target missing revision postId=" + postId + ", version=" + targetVersion);
            recordRebuildFailure(postId, targetVersion, "revision_missing");
            throw new IllegalStateException("缺失目标版本，无法重建 postId=" + postId + ", version=" + targetVersion);
        }
        // 收集从 base 到 target 的链
        List<ContentRevisionEntity> chain = new ArrayList<>();
        ContentRevisionEntity cursor = target;
        chain.add(cursor);
        while (cursor != null && !Boolean.TRUE.equals(cursor.getIsBase())) {
            cursor = contentRepository.findRevision(postId, cursor.getBaseVersion());
            if (cursor != null) {
                chain.add(cursor);
            } else {
                break;
            }
        }
        chain.sort(Comparator.comparing(ContentRevisionEntity::getVersionNum));
        if (chain.size() > MAX_PATCH_HOPS) {
            log.warn("rebuild chain too long, truncate, postId=" + postId + ", targetVersion=" + targetVersion + ", hops=" + chain.size());
            int startIdx = Math.max(0, chain.size() - MAX_PATCH_HOPS);
            chain = new ArrayList<>(chain.subList(startIdx, chain.size()));
            ContentRevisionEntity baseInWindow = chain.stream().filter(r -> Boolean.TRUE.equals(r.getIsBase())).findFirst().orElse(null);
            if (baseInWindow == null) {
                ContentRevisionEntity anchor = chain.get(0);
                ContentRevisionEntity anchorBase = anchor.getBaseVersion() == null ? null : contentRepository.findRevision(postId, anchor.getBaseVersion());
                if (anchorBase != null) {
                    chain.add(0, anchorBase);
                } else {
                    log.error("rebuild chain missing base after truncate postId=" + postId + ", version=" + targetVersion);
                    recordRebuildFailure(postId, targetVersion, "base_missing");
                    throw new IllegalStateException("缺失基准版本，无法重建 postId=" + postId + ", version=" + targetVersion);
                }
            }
            chain.sort(Comparator.comparing(ContentRevisionEntity::getVersionNum));
        }
        // 获取基准全文
        ContentRevisionEntity base = chain.get(0);
        String content = "";
        if (Boolean.TRUE.equals(base.getIsBase())) {
            byte[] chunk = contentRepository.findChunk(base.getChunkHash());
            if (chunk == null) {
                log.error("缺失chunk数据，无法重建内容 postId=" + postId + ", version=" + targetVersion + ", chunk=" + base.getChunkHash());
                recordRebuildFailure(postId, targetVersion, "chunk_missing");
                throw new IllegalStateException("缺失基准数据，无法重建 postId=" + postId + ", version=" + targetVersion);
            }
            content = gunzipToString(chunk);
        }
        // 逐步应用 patch
        for (int i = 1; i < chain.size(); i++) {
            ContentRevisionEntity rev = chain.get(i);
            if (Boolean.TRUE.equals(rev.getIsBase())) {
                byte[] chunk = contentRepository.findChunk(rev.getChunkHash());
                if (chunk == null) {
                    log.error("缺失chunk数据，无法重建内容 postId=" + postId + ", version=" + rev.getVersionNum() + ", chunk=" + rev.getChunkHash());
                    recordRebuildFailure(postId, rev.getVersionNum(), "chunk_missing");
                    return "";
                }
                content = gunzipToString(chunk);
            } else {
                byte[] patchBytes = contentRepository.findPatch(rev.getPatchHash());
                if (patchBytes == null) {
                    log.error("缺失patch数据，无法重建内容 postId=" + postId + ", version=" + rev.getVersionNum() + ", patch=" + rev.getPatchHash());
                    recordRebuildFailure(postId, rev.getVersionNum(), "patch_missing");
                    throw new IllegalStateException("缺失补丁数据，无法重建 postId=" + postId + ", version=" + rev.getVersionNum());
                }
                String diffText = gunzipToString(patchBytes);
                content = applyDiff(content, diffText);
            }
        }
        cachePut(postId, targetVersion, content);
        return content;
    }

    private List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = text.split("\\r?\\n", -1);
        return Arrays.asList(parts);
    }

    private String diff(String prev, String curr) {
        List<String> prevLines = splitLines(prev);
        List<String> currLines = splitLines(curr);
        if (prevLines.size() + currLines.size() > 20000) {
            // 超大文本，直接存基准（返回空 diff）
            return "";
        }
        Patch<String> patch = DiffUtils.diff(prevLines, currLines);
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff("prev", "curr", prevLines, patch, 3);
        return String.join("\n", unified);
    }

    private String applyDiff(String baseText, String diffText) {
        List<String> baseLines = splitLines(baseText);
        List<String> diffLines = splitLines(diffText);
        try {
            Patch<String> patch = UnifiedDiffUtils.parseUnifiedDiff(diffLines);
            List<String> result = DiffUtils.patch(baseLines, patch);
            return String.join("\n", result);
        } catch (Exception e) {
            return baseText;
        }
    }

    private byte[] gzip(String text) {
        if (text == null) {
            text = "";
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
            gzip.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    private String gunzipToString(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             GZIPInputStream gis = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = gis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
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

    private String gzipAndHash(String text) {
        byte[] gz = gzip(text);
        String hash = hash(gz);
        contentRepository.saveChunk(hash, gz, gz.length, "gzip");
        return hash;
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

    private String cacheKey(Long postId, Integer version) {
        return postId + ":" + version;
    }

    private void cachePut(Long postId, Integer version, String content) {
        RLock cacheLock = rebuildCacheLock();
        cacheLock.lock();
        try {
            rebuildCache.put(cacheKey(postId, version), content);
        } finally {
            cacheLock.unlock();
        }
    }

    private RLock lockFor(Long postId) {
        Long key = postId == null ? -1L : postId;
        return redissonClient.getLock(POST_LOCK_KEY_PREFIX + key);
    }

    private RLock rebuildCacheLock() {
        return redissonClient.getLock(REBUILD_CACHE_LOCK_KEY);
    }

    private void recordRebuildFailure(Long postId, Integer version, String reason) {
        int count = rebuildFailureCount.incrementAndGet();
        log.warn("rebuild failure postId=" + postId + ", version=" + version + ", reason=" + reason + ", totalFail=" + count);
    }

    private void assertTx(String scene) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("事务未开启，禁止在非事务环境下调用 " + scene);
        }
    }

    private int countPatchChain(Long postId, Integer targetVersion) {
        int hops = 0;
        ContentRevisionEntity cursor = contentRepository.findRevision(postId, targetVersion);
        while (cursor != null) {
            hops++;
            if (Boolean.TRUE.equals(cursor.getIsBase())) {
                break;
            }
            cursor = contentRepository.findRevision(postId, cursor.getBaseVersion());
            if (hops > 10_000) {
                break;
            }
        }
        return hops;
    }

    private long calcNextDelayMs(int retryCount) {
        long exp = (long) (BACKOFF_BASE_MS * Math.pow(2, Math.min(retryCount, 10)));
        long jitter = (long) (exp * BACKOFF_JITTER_RATE * (Math.random() * 2 - 1));
        long candidate = exp + jitter;
        return Math.max(BACKOFF_BASE_MS, Math.min(BACKOFF_MAX_MS, candidate));
    }
}
