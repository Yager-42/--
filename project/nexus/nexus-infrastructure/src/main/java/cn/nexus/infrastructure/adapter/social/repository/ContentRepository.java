package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentDraftEntity;
import cn.nexus.domain.social.model.entity.ContentHistoryEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jd.platform.hotkey.client.callback.JdHotKeyStore;
import cn.nexus.infrastructure.dao.social.IContentDraftDao;
import cn.nexus.infrastructure.dao.social.IContentHistoryDao;
import cn.nexus.infrastructure.dao.social.IContentPostDao;
import cn.nexus.infrastructure.dao.social.IContentPostTypeDao;
import cn.nexus.infrastructure.dao.social.IContentScheduleDao;
import cn.nexus.infrastructure.dao.social.po.ContentDraftPO;
import cn.nexus.infrastructure.dao.social.po.ContentHistoryPO;
import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.infrastructure.dao.social.po.ContentPostTypePO;
import cn.nexus.infrastructure.dao.social.po.ContentSchedulePO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 内容/媒体仓储 MyBatis 实现。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
public class ContentRepository implements IContentRepository {

    private final IContentDraftDao contentDraftDao;
    private final IContentPostDao contentPostDao;
    private final IContentPostTypeDao contentPostTypeDao;
    private final IContentHistoryDao contentHistoryDao;
    private final IContentScheduleDao contentScheduleDao;

    private static final String HOTKEY_PREFIX = "post__";
    private static final int L1_MAX_SIZE = 100_000;
    private static final Duration L1_TTL = Duration.ofSeconds(2);

    /**
     * Feed 回表热点优化：只缓存热点 postId，短 TTL。
     *
     * <p>注意：缓存对象必须做快照，且对外返回时必须 copy，避免调用方修改污染缓存。</p>
     */
    private final Cache<Long, ContentPostEntity> postCache = Caffeine.newBuilder()
            .maximumSize(L1_MAX_SIZE)
            .expireAfterWrite(L1_TTL)
            .build();

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
        invalidatePostCache(post == null ? null : post.getPostId());
        return post;
    }

    @Override
    public void replacePostTypes(Long postId, List<String> postTypes) {
        if (postId == null) {
            return;
        }
        invalidatePostCache(postId);
        contentPostTypeDao.deleteByPostId(postId);
        if (postTypes == null || postTypes.isEmpty()) {
            return;
        }
        contentPostTypeDao.insertBatch(postId, postTypes);
    }

    @Override
    @Transactional(readOnly = true)
    public ContentPostEntity findPost(Long postId) {
        if (postId == null) {
            return null;
        }

        boolean hot = isHotKeySafe(hotkeyKey(postId));
        if (hot) {
            ContentPostEntity cached = postCache.getIfPresent(postId);
            if (cached != null) {
                return copyPost(cached);
            }
        }

        ContentPostEntity post = toPostEntity(contentPostDao.selectById(postId));
        fillPostTypes(post == null ? List.of() : List.of(post));
        if (hot && post != null) {
            postCache.put(postId, copyPost(post));
        }
        return post;
    }

    @Override
    @Transactional(readOnly = false)
    public ContentPostEntity findPostForUpdate(Long postId) {
        ContentPostEntity post = toPostEntity(contentPostDao.selectByIdForUpdate(postId));
        fillPostTypes(post == null ? List.of() : List.of(post));
        return post;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContentPostEntity> listPostsByIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }

        // L1 只服务“热点 postId”：先查缓存，miss 的再批量回源 DB。
        Map<Long, ContentPostEntity> resultById = new HashMap<>();
        List<Long> missIds = new ArrayList<>(postIds.size());
        for (Long id : postIds) {
            if (id == null) {
                continue;
            }
            boolean hot = isHotKeySafe(hotkeyKey(id));
            if (hot) {
                ContentPostEntity cached = postCache.getIfPresent(id);
                if (cached != null) {
                    resultById.put(id, copyPost(cached));
                    continue;
                }
            }
            missIds.add(id);
        }

        if (!missIds.isEmpty()) {
            // 去重：避免 IN(...) 出现大量重复值（返回顺序由调用方重排，不依赖 DAO）。
            java.util.LinkedHashSet<Long> dedup = new java.util.LinkedHashSet<>(missIds);
            List<Long> queryIds = new ArrayList<>(dedup);
            List<ContentPostPO> list = contentPostDao.selectByIds(queryIds);
            if (list != null && !list.isEmpty()) {
                List<ContentPostEntity> dbEntities = new ArrayList<>(list.size());
                for (ContentPostPO po : list) {
                    ContentPostEntity entity = toPostEntity(po);
                    if (entity == null || entity.getPostId() == null) {
                        continue;
                    }
                    dbEntities.add(entity);
                }
                fillPostTypes(dbEntities);
                for (ContentPostEntity entity : dbEntities) {
                    Long id = entity.getPostId();
                    if (id == null) {
                        continue;
                    }
                    resultById.put(id, entity);
                    if (isHotKeySafe(hotkeyKey(id))) {
                        postCache.put(id, copyPost(entity));
                    }
                }
            }
        }

        List<ContentPostEntity> ordered = new java.util.ArrayList<>(postIds.size());
        for (Long id : postIds) {
            ContentPostEntity entity = resultById.get(id);
            if (entity != null) {
                ordered.add(entity);
            }
        }
        return ordered;
    }

    @Override
    @Transactional(readOnly = true)
    public ContentPostPageVO listUserPosts(Long userId, String cursor, int limit) {
        if (userId == null) {
            return ContentPostPageVO.builder().posts(List.of()).nextCursor(null).build();
        }
        int normalizedLimit = Math.max(1, limit);
        Cursor parsed = Cursor.parse(cursor);
        List<ContentPostPO> list = contentPostDao.selectByUserPage(userId, parsed.cursorTime, parsed.cursorPostId, normalizedLimit);
        if (list == null || list.isEmpty()) {
            return ContentPostPageVO.builder().posts(List.of()).nextCursor(null).build();
        }
        List<ContentPostEntity> posts = list.stream().map(this::toPostEntity).filter(Objects::nonNull).collect(Collectors.toList());
        fillPostTypes(posts);
        ContentPostEntity last = posts.get(posts.size() - 1);
        String nextCursor = last.getCreateTime() == null || last.getPostId() == null
                ? null
                : last.getCreateTime() + ":" + last.getPostId();
        return ContentPostPageVO.builder().posts(posts).nextCursor(nextCursor).build();
    }

    @Override
    public boolean updatePostStatus(Long postId, Integer status, Integer expectedStatus) {
        if (postId == null || status == null || expectedStatus == null) {
            return false;
        }
        int rows = contentPostDao.updateStatusIfMatch(postId, status, expectedStatus);
        boolean updated = rows > 0;
        if (updated) {
            invalidatePostCache(postId);
        }
        return updated;
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
        boolean updated = rows > 0;
        if (updated) {
            invalidatePostCache(postId);
        }
        return updated;
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
        boolean deleted = contentPostDao.updateStatusWithUser(postId, userId, 6) > 0;
        if (deleted) {
            invalidatePostCache(postId);
        }
        return deleted;
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

    private void invalidatePostCache(Long postId) {
        if (postId == null) {
            return;
        }
        postCache.invalidate(postId);
    }

    private ContentPostEntity copyPost(ContentPostEntity post) {
        if (post == null) {
            return null;
        }
        List<String> types = post.getPostTypes();
        List<String> safeTypes = (types == null || types.isEmpty()) ? List.of() : List.copyOf(types);
        return ContentPostEntity.builder()
                .postId(post.getPostId())
                .userId(post.getUserId())
                .contentText(post.getContentText())
                .postTypes(safeTypes)
                .mediaType(post.getMediaType())
                .mediaInfo(post.getMediaInfo())
                .locationInfo(post.getLocationInfo())
                .status(post.getStatus())
                .visibility(post.getVisibility())
                .versionNum(post.getVersionNum())
                .edited(post.getEdited())
                .createTime(post.getCreateTime())
                .build();
    }

    private String hotkeyKey(Long postId) {
        return HOTKEY_PREFIX + postId;
    }

    private boolean isHotKeySafe(String hotkey) {
        try {
            return JdHotKeyStore.isHotKey(hotkey);
        } catch (Exception e) {
            // 外部依赖不可用时，热点治理直接关闭（不影响主链路）。
            log.warn("jd-hotkey isHotKey failed, hotkey={}", hotkey, e);
            return false;
        }
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
                .postTypes(List.of())
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

    private void fillPostTypes(List<ContentPostEntity> posts) {
        if (posts == null || posts.isEmpty()) {
            return;
        }
        List<Long> postIds = new ArrayList<>(posts.size());
        for (ContentPostEntity post : posts) {
            if (post == null || post.getPostId() == null) {
                continue;
            }
            postIds.add(post.getPostId());
        }
        if (postIds.isEmpty()) {
            return;
        }
        List<ContentPostTypePO> rows = contentPostTypeDao.selectByPostIds(postIds);
        Map<Long, List<String>> typesByPostId = new HashMap<>();
        if (rows != null) {
            for (ContentPostTypePO row : rows) {
                if (row == null || row.getPostId() == null) {
                    continue;
                }
                String type = row.getType();
                if (type == null || type.isBlank()) {
                    continue;
                }
                typesByPostId.computeIfAbsent(row.getPostId(), k -> new ArrayList<>()).add(type.trim());
            }
        }
        for (ContentPostEntity post : posts) {
            if (post == null || post.getPostId() == null) {
                continue;
            }
            List<String> types = typesByPostId.get(post.getPostId());
            post.setPostTypes(types == null ? List.of() : types);
        }
    }

    private static final class Cursor {
        private final Date cursorTime;
        private final Long cursorPostId;

        private Cursor(Date cursorTime, Long cursorPostId) {
            this.cursorTime = cursorTime;
            this.cursorPostId = cursorPostId;
        }

        private static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return new Cursor(null, null);
            }
            String[] parts = cursor.split(":", 2);
            if (parts.length != 2) {
                return new Cursor(null, null);
            }
            try {
                long timeMs = Long.parseLong(parts[0]);
                long postId = Long.parseLong(parts[1]);
                return new Cursor(new Date(timeMs), postId);
            } catch (NumberFormatException e) {
                return new Cursor(null, null);
            }
        }
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
