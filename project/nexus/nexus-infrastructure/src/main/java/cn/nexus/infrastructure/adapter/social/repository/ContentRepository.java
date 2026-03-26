package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.port.IContentCacheEvictPort;
import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentDraftEntity;
import cn.nexus.domain.social.model.entity.ContentHistoryEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import cn.nexus.infrastructure.config.HotKeyStoreBridge;
import cn.nexus.infrastructure.config.SocialCacheHotTtlProperties;
import cn.nexus.infrastructure.support.SingleFlight;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
 * 内容/媒体仓储 MyBatis 实现。
 *
 * <p>该仓储同时负责内容主表、草稿、历史版本、定时任务的落库，以及读路径的缓存与回填。</p>
 *
 * @author {$authorName}
 * @since 2026-01-05
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
    private final HotKeyStoreBridge hotKeyStoreBridge;

    private static final String HOTKEY_PREFIX = "post__";
    private static final int L1_MAX_SIZE = 100_000;
    private static final Duration L1_TTL = Duration.ofSeconds(2);

    private static final String POST_REDIS_KEY_PREFIX = "interact:content:post:";
    private static final String POST_REDIS_NULL_VALUE = "NULL";
    // 方案契约：正文缓存 60s 基础 TTL + 0~15s 抖动，避免实现者自行放大范围。
    private static final long POST_REDIS_TTL_SECONDS = 60;
    private static final long POST_REDIS_TTL_JITTER_SECONDS = 15;
    // 方案契约：空值缓存 30s 基础 TTL + 0~10s 抖动，只用于防穿透，不得长期停留。
    private static final long POST_REDIS_NULL_TTL_SECONDS = 30;
    private static final long POST_REDIS_NULL_TTL_JITTER_SECONDS = 10;

    private final SingleFlight singleFlight = new SingleFlight();

    /**
     * Feed 回表热点优化：只缓存热点 postId，短 TTL。
     *
     * <p>注意：缓存对象必须做快照，且对外返回时必须 copy，避免调用方修改污染缓存。</p>
     */
    private final Cache<Long, ContentPostEntity> postCache = Caffeine.newBuilder()
            .maximumSize(L1_MAX_SIZE)
            .expireAfterWrite(L1_TTL)
            .build();

    /**
     * 保存草稿（upsert）。
     *
     * @param draft 草稿实体 {@link ContentDraftEntity}
     * @return 保存后的草稿实体 {@link ContentDraftEntity}
     */
    @Override
    public ContentDraftEntity saveDraft(ContentDraftEntity draft) {
        contentDraftDao.insertOrUpdate(toDraftPO(draft));
        return draft;
    }

    /**
     * 查询草稿。
     *
     * @param draftId 草稿 ID {@link Long}
     * @return 草稿实体（不存在则返回 {@code null}） {@link ContentDraftEntity}
     */
    @Override
    @Transactional(readOnly = true)
    public ContentDraftEntity findDraft(Long draftId) {
        ContentDraftPO po = contentDraftDao.selectById(draftId);
        return toDraftEntity(po);
    }

    /**
     * 查询草稿并加行锁（用于同步草稿时的并发控制）。
     *
     * @param draftId 草稿 ID {@link Long}
     * @return 草稿实体（不存在则返回 {@code null}） {@link ContentDraftEntity}
     */
    @Override
    @Transactional(readOnly = false)
    public ContentDraftEntity findDraftForUpdate(Long draftId) {
        ContentDraftPO po = contentDraftDao.selectByIdForUpdate(draftId);
        return toDraftEntity(po);
    }

    /**
     * 保存内容主表记录（仅主表字段）。
     *
     * <p>保存后会失效缓存，避免读到旧值。</p>
     *
     * @param post 内容主表实体 {@link ContentPostEntity}
     * @return 保存后的内容主表实体 {@link ContentPostEntity}
     */
    @Transactional
    @Override
    public ContentPostEntity savePost(ContentPostEntity post) {
        contentPostDao.insert(toPostPO(post));
        invalidatePostCache(post == null ? null : post.getPostId());
        return post;
    }

    /**
     * 替换帖子类型列表（先删后插）。
     *
     * @param postId 帖子 ID {@link Long}
     * @param postTypes 类型列表（可为空） {@link List}
     */
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

    /**
     * 查询帖子完整信息（主表 + 类型 + 正文）。
     *
     * <p>读取策略：L1 Caffeine + Redis + SingleFlight 回源，带空值缓存与热点续命。</p>
     *
     * @param postId 帖子 ID {@link Long}
     * @return 帖子实体（不存在则返回 {@code null}） {@link ContentPostEntity}
     */
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

        String redisKey = postRedisKey(postId);
        String cached = null;
        try {
            cached = stringRedisTemplate.opsForValue().get(redisKey);
        } catch (Exception e) {
            log.warn("findPost redis get failed, key={}", redisKey, e);
        }

        if (POST_REDIS_NULL_VALUE.equals(cached)) {
            return null;
        }
        if (cached != null) {
            if (cached.isBlank()) {
                deleteRedisQuietly(redisKey);
            } else {
                ContentPostEntity entity = parsePostCache(cached);
                if (entity != null && entity.getPostId() != null) {
                    if (hot) {
                        postCache.put(postId, copyPost(entity));
                    }
                    tryExtendHotCacheTtl(postId, entity);
                    return copyPost(entity);
                }
                deleteRedisQuietly(redisKey);
            }
        }

        ContentPostEntity post = singleFlight.execute(findPostInflightKey(postId), () -> {
            String secondCheck = null;
            try {
                secondCheck = stringRedisTemplate.opsForValue().get(redisKey);
            } catch (Exception ignored) {
            }

            if (POST_REDIS_NULL_VALUE.equals(secondCheck)) {
                return null;
            }
            if (secondCheck != null) {
                if (secondCheck.isBlank()) {
                    deleteRedisQuietly(redisKey);
                } else {
                    ContentPostEntity entity = parsePostCache(secondCheck);
                    if (entity != null && entity.getPostId() != null) {
                        return entity;
                    }
                    deleteRedisQuietly(redisKey);
                }
            }

            ContentPostEntity rebuilt = toPostEntity(contentPostDao.selectById(postId));
            fillPostTypes(rebuilt == null ? List.of() : List.of(rebuilt));
            fillPostContent(rebuilt == null ? List.of() : List.of(rebuilt));
            if (rebuilt == null) {
                try {
                    stringRedisTemplate.opsForValue().set(redisKey, POST_REDIS_NULL_VALUE, nullTtlSeconds(), TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // ignore
                }
                return null;
            }

            String json = serializePostCache(rebuilt);
            if (json != null) {
                try {
                    stringRedisTemplate.opsForValue().set(redisKey, json, positiveTtlSeconds(), TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("findPost redis set failed, key={}", redisKey, e);
                }
            }
            return rebuilt;
        });
        if (hot && post != null) {
            postCache.put(postId, copyPost(post));
        }
        if (post != null) {
            tryExtendHotCacheTtl(postId, post);
        }
        return post == null ? null : copyPost(post);
    }

    @Override
    @Transactional(readOnly = true)
    public ContentPostEntity findPostBypassCache(Long postId) {
        if (postId == null) {
            return null;
        }
        ContentPostEntity rebuilt = toPostEntity(contentPostDao.selectById(postId));
        fillPostTypes(rebuilt == null ? List.of() : List.of(rebuilt));
        fillPostContent(rebuilt == null ? List.of() : List.of(rebuilt));
        return rebuilt;
    }

    /**
     * 查询帖子并加行锁（用于发布/回滚等写链路）。
     *
     * @param postId 帖子 ID {@link Long}
     * @return 帖子实体（不存在则返回 {@code null}） {@link ContentPostEntity}
     */
    @Override
    @Transactional(readOnly = false)
    public ContentPostEntity findPostForUpdate(Long postId) {
        ContentPostEntity post = toPostEntity(contentPostDao.selectByIdForUpdate(postId));
        fillPostTypes(post == null ? List.of() : List.of(post));
        fillPostContent(post == null ? List.of() : List.of(post));
        return post;
    }

    /**
     * 查询帖子元信息（不回填正文 KV）。
     *
     * @param postId 帖子 ID {@link Long}
     * @return 帖子实体（不含正文，可能为 {@code null}） {@link ContentPostEntity}
     */
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

    /**
     * 批量按 ID 查询帖子。
     *
     * @param postIds 帖子 ID 列表 {@link List}
     * @return 帖子列表（元素为 {@link ContentPostEntity}） {@link List}
     */
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
                Map<Long, ContentPostEntity> rebuilt = singleFlight.execute(listPostsInflightKey(dbMissIds),
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
            if (entity != null && entity.getStatus() != null && entity.getStatus() == 2) {
                ordered.add(copyPost(entity));
                tryExtendHotCacheTtl(id, entity);
            }
        }
        return ordered;
    }

    /**
     * 分页查询用户已发布帖子列表。
     *
     * @param userId 用户 ID {@link Long}
     * @param cursor 游标（可为空） {@link String}
     * @param limit 分页大小 {@code int}
     * @return 分页结果 {@link ContentPostPageVO}
     */
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

    /**
     * 更新帖子状态（带期望状态条件）。
     *
     * <p>更新成功会主动失效缓存，避免读到旧状态。</p>
     *
     * @param postId 帖子 ID {@link Long}
     * @param status 目标状态 {@link Integer}
     * @param expectedStatus 期望当前状态（CAS 条件） {@link Integer}
     * @return 是否更新成功 {@code boolean}
     */
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

    /**
     * 更新帖子状态（带期望状态与期望版本号条件）。
     *
     * @param postId 帖子 ID {@link Long}
     * @param status 目标状态 {@link Integer}
     * @param expectedStatus 期望当前状态（CAS 条件） {@link Integer}
     * @param expectedVersion 期望当前版本号（CAS 条件） {@link Integer}
     * @return 是否更新成功 {@code boolean}
     */
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

    /**
     * 更新帖子状态与发布时间（带期望状态与期望版本号条件）。
     *
     * @param postId 帖子 ID {@link Long}
     * @param status 目标状态 {@link Integer}
     * @param expectedStatus 期望当前状态（CAS 条件） {@link Integer}
     * @param expectedVersion 期望当前版本号（CAS 条件） {@link Integer}
     * @param publishTime 发布时间（毫秒时间戳） {@link Long}
     * @return 是否更新成功 {@code boolean}
     */
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

    /**
     * 更新帖子摘要与摘要状态。
     *
     * @param postId 帖子 ID {@link Long}
     * @param summary 摘要内容（可为空） {@link String}
     * @param summaryStatus 摘要状态 {@link Integer}
     * @return 是否更新成功 {@code boolean}
     */
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

    /**
     * 更新帖子内容与版本号（带 expectedVersion 条件，防止并发乱序覆盖）。
     *
     * <p>该方法会把 {@code expectedVersion = versionNum - 1} 作为更新条件，确保版本号单调递增。</p>
     *
     * @param postId 帖子 ID {@link Long}
     * @param status 目标状态 {@link Integer}
     * @param versionNum 目标版本号 {@link Integer}
     * @param edited 是否编辑过（可为空） {@link Boolean}
     * @param title 标题（可为空） {@link String}
     * @param publishTime 发布时间（毫秒时间戳，可为空） {@link Long}
     * @param contentUuid 正文 KV UUID（可为空） {@link String}
     * @param mediaInfo 媒体信息（可为空） {@link String}
     * @param locationInfo 位置信息（可为空） {@link String}
     * @param visibility 可见性（可为空） {@link Integer}
     * @return 是否更新成功 {@code boolean}
     */
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

    /**
     * 保存历史版本快照。
     *
     * @param history 历史版本实体 {@link ContentHistoryEntity}
     */
    @Override
    public void saveHistory(ContentHistoryEntity history) {
        contentHistoryDao.insert(toHistoryPO(history));
    }

    /**
     * 查询历史版本列表。
     *
     * @param postId 帖子 ID {@link Long}
     * @param limit 分页大小（可为空） {@link Integer}
     * @param offset 分页偏移（可为空） {@link Integer}
     * @return 历史版本列表（元素为 {@link ContentHistoryEntity}） {@link List}
     */
    @Override
    @Transactional(readOnly = true)
    public List<ContentHistoryEntity> listHistory(Long postId, Integer limit, Integer offset) {
        return contentHistoryDao.selectByPostId(postId, limit, offset).stream()
                .map(this::toHistoryEntity)
                .collect(Collectors.toList());
    }

    /**
     * 查询指定版本号的历史快照。
     *
     * @param postId 帖子 ID {@link Long}
     * @param versionNum 版本号 {@link Integer}
     * @return 历史版本实体（不存在则返回 {@code null}） {@link ContentHistoryEntity}
     */
    @Override
    @Transactional(readOnly = true)
    public ContentHistoryEntity findHistoryVersion(Long postId, Integer versionNum) {
        return toHistoryEntity(contentHistoryDao.selectOne(postId, versionNum));
    }

    /**
     * 软删除帖子（按 userId 校验归属）。
     *
     * @param postId 帖子 ID {@link Long}
     * @param userId 用户 ID {@link Long}
     * @return 是否删除成功 {@code boolean}
     */
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

    /**
     * 软删除帖子（带期望状态与期望版本号条件）。
     *
     * @param postId 帖子 ID {@link Long}
     * @param expectedStatus 期望当前状态（CAS 条件） {@link Integer}
     * @param expectedVersion 期望当前版本号（CAS 条件） {@link Integer}
     * @param deleteTimeMs 删除时间（毫秒时间戳，可为空） {@link Long}
     * @return 是否删除成功 {@code boolean}
     */
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

    /**
     * 物理删除软删超过截止时间的帖子（批量）。
     *
     * <p>该清理只删除主表与类型映射，历史表保留用于审计。</p>
     *
     * @param cutoff 截止时间 {@link Date}
     * @param limit 单次清理上限 {@code int}
     * @return 删除行数 {@code int}
     */
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
            // 先清理关联映射，避免产生脏数据。
            contentPostTypeDao.deleteByPostId(postId);
            invalidatePostCache(postId);
        }
        return contentPostDao.deleteSoftDeletedByIds(postIds, cutoff);
    }

    /**
     * 创建一条定时发布任务。
     *
     * @param schedule 定时任务实体 {@link ContentScheduleEntity}
     * @return 创建后的定时任务实体 {@link ContentScheduleEntity}
     */
    @Override
    public ContentScheduleEntity createSchedule(ContentScheduleEntity schedule) {
        contentScheduleDao.insert(toSchedulePO(schedule));
        return schedule;
    }

    /**
     * 更新定时任务状态（带 expectedStatus 条件）。
     *
     * @param taskId 任务 ID {@link Long}
     * @param status 目标状态 {@link Integer}
     * @param retryCount 重试次数（可为空） {@link Integer}
     * @param lastError 最近一次错误（可为空） {@link String}
     * @param alarmSent 是否已发送告警（可为空） {@link Integer}
     * @param nextScheduleTime 下一次调度时间（毫秒时间戳，可为空） {@link Long}
     * @param expectedStatus 期望当前状态（CAS 条件） {@link Integer}
     * @return 是否更新成功 {@code boolean}
     */
    @Override
    public boolean updateScheduleStatus(Long taskId, Integer status, Integer retryCount, String lastError, Integer alarmSent, Long nextScheduleTime, Integer expectedStatus) {
        return contentScheduleDao.updateStatus(taskId, status, retryCount, lastError, alarmSent,
                nextScheduleTime == null ? null : new Date(nextScheduleTime), expectedStatus) > 0;
    }

    /**
     * 查询待执行的定时任务列表。
     *
     * @param beforeTime 截止时间（毫秒时间戳，可为空） {@link Long}
     * @param limit 查询上限（可为空） {@link Integer}
     * @return 定时任务列表（元素为 {@link ContentScheduleEntity}） {@link List}
     */
    @Override
    public List<ContentScheduleEntity> listPendingSchedules(Long beforeTime, Integer limit) {
        List<ContentSchedulePO> list = contentScheduleDao.selectPending(beforeTime == null ? null : new Date(beforeTime), limit);
        if (list == null) {
            return java.util.List.of();
        }
        return list.stream().map(this::toScheduleEntity).collect(Collectors.toList());
    }

    /**
     * 查询定时任务。
     *
     * @param taskId 任务 ID {@link Long}
     * @return 定时任务实体（不存在则返回 {@code null}） {@link ContentScheduleEntity}
     */
    @Override
    @Transactional(readOnly = true)
    public ContentScheduleEntity findSchedule(Long taskId) {
        return toScheduleEntity(contentScheduleDao.selectById(taskId));
    }

    /**
     * 取消定时任务（仅允许取消“待发布”状态的任务）。
     *
     * @param taskId 任务 ID {@link Long}
     * @param userId 用户 ID {@link Long}
     * @param reason 取消原因（可为空） {@link String}
     * @return 是否取消成功 {@code boolean}
     */
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

    /**
     * 按幂等 Token 查询定时任务。
     *
     * @param token 幂等 Token {@link String}
     * @return 定时任务实体（不存在则返回 {@code null}） {@link ContentScheduleEntity}
     */
    @Override
    @Transactional(readOnly = true)
    public ContentScheduleEntity findScheduleByToken(String token) {
        return toScheduleEntity(contentScheduleDao.selectByToken(token));
    }

    /**
     * 更新定时任务（目前允许更新时间与内容快照）。
     *
     * @param taskId 任务 ID {@link Long}
     * @param userId 用户 ID {@link Long}
     * @param scheduleTime 新的调度时间（毫秒时间戳，可为空） {@link Long}
     * @param contentData 内容快照（可为空） {@link String}
     * @param idempotentToken 幂等 Token（可为空） {@link String}
     * @param reason 更新原因（可为空） {@link String}
     * @return 是否更新成功 {@code boolean}
     */
    @Override
    public boolean updateSchedule(Long taskId, Long userId, Long scheduleTime, String contentData, String idempotentToken, String reason) {
        return contentScheduleDao.updateSchedule(taskId, userId, scheduleTime == null ? null : new Date(scheduleTime), contentData, idempotentToken, reason) > 0;
    }

    /**
     * 查询某帖子当前激活中的定时任务（若存在）。
     *
     * @param postId 帖子 ID {@link Long}
     * @return 激活中的定时任务（不存在则返回 {@code null}） {@link ContentScheduleEntity}
     */
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
     * 仅失效本地 L1 缓存（用于 MQ 广播后的本地清理）。
     *
     * @param postId 帖子 ID {@link Long}
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
                String json = serializePostCache(entity);
                if (json != null) {
                    valueOps.set(postRedisKey(id), json, positiveTtlSeconds(), TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
                // 缓存写失败视为 miss，不影响主链路。
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
            stringRedisTemplate.expire(key, targetTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 热点延寿失败不影响主链路。
        }
    }

    private long positiveTtlSeconds() {
        return POST_REDIS_TTL_SECONDS
                + ThreadLocalRandom.current().nextLong(POST_REDIS_TTL_JITTER_SECONDS + 1);
    }

    private long nullTtlSeconds() {
        return POST_REDIS_NULL_TTL_SECONDS
                + ThreadLocalRandom.current().nextLong(POST_REDIS_NULL_TTL_JITTER_SECONDS + 1);
    }

    private String serializePostCache(ContentPostEntity post) {
        if (post == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(post);
        } catch (Exception e) {
            log.warn("serialize post cache failed, postId={}", post.getPostId(), e);
            return null;
        }
    }

    private String findPostInflightKey(Long postId) {
        return "findPost:" + postId;
    }

    private String listPostsInflightKey(List<Long> ids) {
        return "listPosts:" + normalizeInflightKey(ids);
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
            return hotKeyStoreBridge.isHotKey(hotkey);
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
