package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.port.IContentCacheEvictPort;
import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentDraftEntity;
import cn.nexus.domain.social.model.entity.ContentHistoryEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import cn.nexus.infrastructure.config.SocialCacheHotTtlProperties;
import cn.nexus.infrastructure.support.SingleFlight;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 鍐呭/濯掍綋浠撳偍 MyBatis 瀹炵幇銆?
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
    private final IPostContentKvPort postContentKvPort;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<IContentCacheEvictPort> contentCacheEvictPortProvider;
    private final SocialCacheHotTtlProperties socialCacheHotTtlProperties;

    private static final String HOTKEY_PREFIX = "post__";
    private static final int L1_MAX_SIZE = 100_000;
    private static final Duration L1_TTL = Duration.ofSeconds(2);

    private static final String POST_REDIS_KEY_PREFIX = "interact:content:post:";
    private static final String POST_REDIS_NULL_VALUE = "NULL";
    private static final long POST_REDIS_TTL_SECONDS = 60;
    private static final long POST_REDIS_NULL_TTL_SECONDS = 30;

    private final SingleFlight singleFlight = new SingleFlight();

    /**
     * Feed 鍥炶〃鐑偣浼樺寲锛氬彧缂撳瓨鐑偣 postId锛岀煭 TTL銆?
     *
     * <p>娉ㄦ剰锛氱紦瀛樺璞″繀椤诲仛蹇収锛屼笖瀵瑰杩斿洖鏃跺繀椤?copy锛岄伩鍏嶈皟鐢ㄦ柟淇敼姹℃煋缂撳瓨銆?/p>
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

    @Override
    @Transactional(readOnly = false)
    public ContentDraftEntity findDraftForUpdate(Long draftId) {
        ContentDraftPO po = contentDraftDao.selectByIdForUpdate(draftId);
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
        fillPostContent(post == null ? List.of() : List.of(post));
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
        fillPostContent(post == null ? List.of() : List.of(post));
        return post;
    }

    @Override
    @Transactional(readOnly = true)
    public ContentPostEntity findPostMeta(Long postId) {
        if (postId == null) {
            return null;
        }
        ContentPostEntity post = toPostEntity(contentPostDao.selectById(postId));
        fillPostTypes(post == null ? List.of() : List.of(post));
        return post;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContentPostEntity> listPostsByIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }

        ValueOperations<String, String> valueOps = stringRedisTemplate.opsForValue();
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
            LinkedHashSet<Long> dedup = new LinkedHashSet<>(missIds);
            List<Long> uniqMissIds = new ArrayList<>(dedup);
            List<Long> dbMissIds = new ArrayList<>(uniqMissIds.size());
            try {
                List<String> keys = new ArrayList<>(uniqMissIds.size());
                for (Long id : uniqMissIds) {
                    keys.add(postRedisKey(id));
                }
                List<String> cached = valueOps.multiGet(keys);
                if (cached != null && cached.size() == keys.size()) {
                    for (int i = 0; i < uniqMissIds.size(); i++) {
                        Long id = uniqMissIds.get(i);
                        String json = cached.get(i);
                        if (json == null) {
                            dbMissIds.add(id);
                            continue;
                        }
                        if (POST_REDIS_NULL_VALUE.equals(json)) {
                            continue;
                        }
                        ContentPostEntity entity = parsePostCache(json);
                        if (entity == null || entity.getPostId() == null) {
                            deleteRedisQuietly(postRedisKey(id));
                            dbMissIds.add(id);
                            continue;
                        }
                        resultById.put(id, copyPost(entity));
                        if (isHotKeySafe(hotkeyKey(id))) {
                            postCache.put(id, copyPost(entity));
                        }
                    }
                } else {
                    dbMissIds.addAll(uniqMissIds);
                }
            } catch (Exception ignored) {
                dbMissIds.addAll(uniqMissIds);
            }

            if (!dbMissIds.isEmpty()) {
                Map<Long, ContentPostEntity> rebuilt = singleFlight.execute(normalizeInflightKey(dbMissIds),
                        () -> rebuildPosts(dbMissIds, valueOps));
                if (rebuilt != null && !rebuilt.isEmpty()) {
                    for (Map.Entry<Long, ContentPostEntity> entry : rebuilt.entrySet()) {
                        if (entry.getKey() == null || entry.getValue() == null) {
                            continue;
                        }
                        resultById.put(entry.getKey(), copyPost(entry.getValue()));
                    }
                }
            }
        }

        List<ContentPostEntity> ordered = new ArrayList<>(postIds.size());
        for (Long id : postIds) {
            ContentPostEntity entity = resultById.get(id);
            if (entity != null) {
                ordered.add(copyPost(entity));
                tryExtendHotCacheTtl(id, entity);
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
        fillPostContent(posts);
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
    public boolean updatePostStatusIfMatchVersion(Long postId, Integer status, Integer expectedStatus, Integer expectedVersion) {
        if (postId == null || status == null || expectedStatus == null || expectedVersion == null) {
            return false;
        }
        int rows = contentPostDao.updateStatusIfMatchAndVersion(postId, status, expectedStatus, expectedVersion);
        boolean updated = rows > 0;
        if (updated) {
            invalidatePostCache(postId);
        }
        return updated;
    }

    @Override
    public boolean updatePostStatusAndPublishTimeIfMatchVersion(Long postId, Integer status, Integer expectedStatus, Integer expectedVersion, Long publishTime) {
        if (postId == null || status == null || expectedStatus == null || expectedVersion == null || publishTime == null) {
            return false;
        }
        int rows = contentPostDao.updateStatusAndPublishTimeIfMatchAndVersion(
                postId,
                status,
                new Date(publishTime),
                expectedStatus,
                expectedVersion);
        boolean updated = rows > 0;
        if (updated) {
            invalidatePostCache(postId);
        }
        return updated;
    }

    @Override
    public boolean updatePostSummary(Long postId, String summary, Integer summaryStatus) {
        if (postId == null || summaryStatus == null) {
            return false;
        }
        int rows = contentPostDao.updateSummary(postId, summary, summaryStatus);
        boolean updated = rows > 0;
        if (updated) {
            invalidatePostCache(postId);
        }
        return updated;
    }

    @Override
    public boolean updatePostStatusAndContent(Long postId, Integer status, Integer versionNum, Boolean edited,
                                              String title, Long publishTime, String contentUuid, String mediaInfo,
                                              String locationInfo, Integer visibility) {
        Integer expectedVersion = versionNum == null ? null : Math.max(0, versionNum - 1);
        int rows = contentPostDao.updateContentAndVersion(
                postId,
                title,
                publishTime == null ? null : new Date(publishTime),
                contentUuid,
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
    public boolean softDeleteIfMatchStatusAndVersion(Long postId, Integer expectedStatus, Integer expectedVersion, Long deleteTimeMs) {
        if (postId == null || expectedStatus == null || expectedVersion == null) {
            return false;
        }
        Date deleteTime = deleteTimeMs == null ? new Date() : new Date(deleteTimeMs);
        boolean deleted = contentPostDao.softDeleteIfMatchAndVersion(postId, deleteTime, expectedStatus, expectedVersion) > 0;
        if (deleted) {
            invalidatePostCache(postId);
        }
        return deleted;
    }

    @Override
    public int deleteSoftDeletedBefore(Date cutoff, int limit) {
        if (cutoff == null || limit <= 0) {
            return 0;
        }
        List<Long> postIds = contentPostDao.selectSoftDeletedIdsBefore(cutoff, limit);
        if (postIds == null || postIds.isEmpty()) {
            return 0;
        }
        for (Long postId : postIds) {
            if (postId == null) {
                continue;
            }
            // 鍏堟竻鐞嗗叧鑱旀槧灏勶紝閬垮厤浜х敓鑴忔暟鎹€?
            contentPostTypeDao.deleteByPostId(postId);
            invalidatePostCache(postId);
        }
        return contentPostDao.deleteSoftDeletedByIds(postIds, cutoff);
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

    @Override
    @Transactional(readOnly = true)
    public ContentScheduleEntity findActiveScheduleByPostId(Long postId) {
        return toScheduleEntity(contentScheduleDao.selectActiveByPostId(postId));
    }

    private void invalidatePostCache(Long postId) {
        if (postId == null) {
            return;
        }
        IContentCacheEvictPort cacheEvictPort = contentCacheEvictPortProvider.getIfAvailable();
        if (cacheEvictPort != null) {
            cacheEvictPort.evictPost(postId);
            return;
        }
        evictLocalPostCache(postId);
        deleteRedisQuietly(postRedisKey(postId));
    }

    /**
     * Evict local L1 only (for MQ broadcast).
     */
    public void evictLocalPostCache(Long postId) {
        if (postId == null) {
            return;
        }
        postCache.invalidate(postId);
    }

    private String postRedisKey(Long postId) {
        return POST_REDIS_KEY_PREFIX + postId;
    }

    private Map<Long, ContentPostEntity> rebuildPosts(List<Long> dbMissIds, ValueOperations<String, String> valueOps) {
        Map<Long, ContentPostEntity> resolved = readPostsFromRedis(dbMissIds, true);
        List<Long> unresolvedIds = new ArrayList<>();
        for (Long id : dbMissIds) {
            if (id == null || resolved.containsKey(id)) {
                continue;
            }
            unresolvedIds.add(id);
        }
        if (unresolvedIds.isEmpty()) {
            return resolved;
        }

        List<ContentPostPO> list = contentPostDao.selectByIds(unresolvedIds);
        List<ContentPostEntity> dbEntities = new ArrayList<>(list == null ? 0 : list.size());
        if (list != null && !list.isEmpty()) {
            for (ContentPostPO po : list) {
                ContentPostEntity entity = toPostEntity(po);
                if (entity == null || entity.getPostId() == null) {
                    continue;
                }
                dbEntities.add(entity);
            }
        }
        fillPostTypes(dbEntities);
        fillPostContent(dbEntities);

        Map<Long, ContentPostEntity> dbHits = new HashMap<>();
        for (ContentPostEntity entity : dbEntities) {
            Long id = entity.getPostId();
            if (id == null) {
                continue;
            }
            dbHits.put(id, copyPost(entity));
            resolved.put(id, copyPost(entity));
            if (isHotKeySafe(hotkeyKey(id))) {
                postCache.put(id, copyPost(entity));
            }
            try {
                String json = objectMapper.writeValueAsString(entity);
                valueOps.set(postRedisKey(id), json, positiveTtlSeconds(), TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 缓存写失败视为 miss，不影响主链路。
            }
        }

        for (Long id : unresolvedIds) {
            if (id == null || dbHits.containsKey(id)) {
                continue;
            }
            try {
                valueOps.set(postRedisKey(id), POST_REDIS_NULL_VALUE, POST_REDIS_NULL_TTL_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // ignore
            }
        }
        return resolved;
    }

    private Map<Long, ContentPostEntity> readPostsFromRedis(List<Long> postIds, boolean deleteBrokenKey) {
        Map<Long, ContentPostEntity> resolved = new HashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return resolved;
        }
        List<String> keys = new ArrayList<>(postIds.size());
        for (Long id : postIds) {
            if (id != null) {
                keys.add(postRedisKey(id));
            }
        }
        if (keys.isEmpty()) {
            return resolved;
        }
        List<String> cached;
        try {
            cached = stringRedisTemplate.opsForValue().multiGet(keys);
        } catch (Exception ignored) {
            return resolved;
        }
        if (cached == null || cached.size() != keys.size()) {
            return resolved;
        }
        int idx = 0;
        for (Long id : postIds) {
            if (id == null) {
                continue;
            }
            String json = cached.get(idx++);
            if (json == null || POST_REDIS_NULL_VALUE.equals(json)) {
                continue;
            }
            ContentPostEntity entity = parsePostCache(json);
            if (entity == null || entity.getPostId() == null) {
                if (deleteBrokenKey) {
                    deleteRedisQuietly(postRedisKey(id));
                }
                continue;
            }
            resolved.put(id, copyPost(entity));
            if (isHotKeySafe(hotkeyKey(id))) {
                postCache.put(id, copyPost(entity));
            }
        }
        return resolved;
    }

    private void tryExtendHotCacheTtl(Long postId, ContentPostEntity entity) {
        if (postId == null || entity == null || entity.getPostId() == null) {
            return;
        }
        long targetTtlSeconds = socialCacheHotTtlProperties.getContentPostSeconds();
        if (targetTtlSeconds <= 0 || !isHotKeySafe(hotkeyKey(postId))) {
            return;
        }
        String key = postRedisKey(postId);
        try {
            Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (ttl == null || ttl <= 0 || ttl >= targetTtlSeconds) {
                return;
            }
            String raw = stringRedisTemplate.opsForValue().get(key);
            if (raw == null || POST_REDIS_NULL_VALUE.equals(raw)) {
                return;
            }
            stringRedisTemplate.expire(key, targetTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 热点延寿失败不影响主链路。
        }
    }

    private long positiveTtlSeconds() {
        return POST_REDIS_TTL_SECONDS + ThreadLocalRandom.current().nextLong(0, POST_REDIS_TTL_SECONDS + 1);
    }

    private String normalizeInflightKey(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private void deleteRedisQuietly(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private ContentPostEntity parsePostCache(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            ContentPostEntity entity = objectMapper.readValue(json, ContentPostEntity.class);
            if (entity != null && entity.getPostTypes() == null) {
                entity.setPostTypes(List.of());
            }
            return entity;
        } catch (Exception ignored) {
            return null;
        }
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
                .title(post.getTitle())
                .contentUuid(post.getContentUuid())
                .contentText(post.getContentText())
                .summary(post.getSummary())
                .summaryStatus(post.getSummaryStatus())
                .postTypes(safeTypes)
                .mediaType(post.getMediaType())
                .mediaInfo(post.getMediaInfo())
                .locationInfo(post.getLocationInfo())
                .status(post.getStatus())
                .visibility(post.getVisibility())
                .versionNum(post.getVersionNum())
                .edited(post.getEdited())
                .createTime(post.getCreateTime())
                .publishTime(post.getPublishTime())
                .build();
    }

    private String hotkeyKey(Long postId) {
        return HOTKEY_PREFIX + postId;
    }

    private boolean isHotKeySafe(String hotkey) {
        try {
            return JdHotKeyStore.isHotKey(hotkey);
        } catch (Exception e) {
            // 澶栭儴渚濊禆涓嶅彲鐢ㄦ椂锛岀儹鐐规不鐞嗙洿鎺ュ叧闂紙涓嶅奖鍝嶄富閾捐矾锛夈€?
            log.warn("jd-hotkey isHotKey failed, hotkey={}", hotkey, e);
            return false;
        }
    }


    private ContentDraftPO toDraftPO(ContentDraftEntity entity) {
        ContentDraftPO po = new ContentDraftPO();
        po.setDraftId(entity.getDraftId());
        po.setUserId(entity.getUserId());
        po.setTitle(entity.getTitle());
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
                .title(po.getTitle())
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
        po.setTitle(entity.getTitle());
        po.setContentUuid(entity.getContentUuid());
        po.setSummary(entity.getSummary());
        po.setSummaryStatus(entity.getSummaryStatus());
        po.setMediaType(entity.getMediaType());
        po.setMediaInfo(entity.getMediaInfo());
        po.setLocationInfo(entity.getLocationInfo());
        po.setStatus(entity.getStatus());
        po.setVisibility(entity.getVisibility());
        po.setVersionNum(entity.getVersionNum());
        po.setIsEdited(Boolean.TRUE.equals(entity.getEdited()) ? 1 : 0);
        po.setCreateTime(entity.getCreateTime() == null ? null : new Date(entity.getCreateTime()));
        po.setPublishTime(entity.getPublishTime() == null ? null : new Date(entity.getPublishTime()));
        return po;
    }

    private ContentPostEntity toPostEntity(ContentPostPO po) {
        if (po == null) {
            return null;
        }
        return ContentPostEntity.builder()
                .postId(po.getPostId())
                .userId(po.getUserId())
                .title(po.getTitle())
                .contentUuid(po.getContentUuid())
                .contentText(null)
                .summary(po.getSummary())
                .summaryStatus(po.getSummaryStatus())
                .postTypes(List.of())
                .mediaType(po.getMediaType())
                .mediaInfo(po.getMediaInfo())
                .locationInfo(po.getLocationInfo())
                .status(po.getStatus())
                .visibility(po.getVisibility())
                .versionNum(po.getVersionNum())
                .edited(Objects.equals(po.getIsEdited(), 1))
                .createTime(po.getCreateTime() == null ? null : po.getCreateTime().getTime())
                .publishTime(po.getPublishTime() == null ? null : po.getPublishTime().getTime())
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

    private void fillPostContent(List<ContentPostEntity> posts) {
        if (posts == null || posts.isEmpty()) {
            return;
        }

        java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>();
        for (ContentPostEntity post : posts) {
            if (post == null) {
                continue;
            }
            if (post.getContentText() != null) {
                continue;
            }
            String uuid = post.getContentUuid();
            if (uuid == null || uuid.isBlank()) {
                continue;
            }
            dedup.add(uuid.trim());
        }
        if (dedup.isEmpty()) {
            return;
        }

        List<String> uuids = new ArrayList<>(dedup);
        Map<String, String> contentByUuid;
        try {
            contentByUuid = postContentKvPort.findBatch(uuids);
        } catch (Exception e) {
            // KV 涓嶅彲鐢ㄨ涓烘鏂囩己澶憋紝涓嶅奖鍝嶄富閾捐矾
            log.warn("fill post content from kv failed, size={}", uuids.size(), e);
            return;
        }
        if (contentByUuid == null || contentByUuid.isEmpty()) {
            return;
        }

        for (ContentPostEntity post : posts) {
            if (post == null) {
                continue;
            }
            if (post.getContentText() != null) {
                continue;
            }
            String uuid = post.getContentUuid();
            if (uuid == null || uuid.isBlank()) {
                continue;
            }
            post.setContentText(contentByUuid.get(uuid.trim()));
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
        po.setSnapshotTitle(entity.getSnapshotTitle());
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
                .snapshotTitle(po.getSnapshotTitle())
                .snapshotContent(po.getSnapshotContent())
                .snapshotMedia(po.getSnapshotMedia())
                .createTime(po.getCreateTime() == null ? null : po.getCreateTime().getTime())
                .build();
    }

    private ContentSchedulePO toSchedulePO(ContentScheduleEntity entity) {
        ContentSchedulePO po = new ContentSchedulePO();
        po.setTaskId(entity.getTaskId());
        po.setUserId(entity.getUserId());
        po.setPostId(entity.getPostId());
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
                .postId(po.getPostId())
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
