package cn.nexus.infrastructure.adapter.social.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.ICommentContentKvPort;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.CommentViewVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentItemVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentKeyVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentResultVO;
import cn.nexus.infrastructure.dao.social.ICommentDao;
import cn.nexus.infrastructure.dao.social.po.CommentPO;
import cn.nexus.infrastructure.support.SingleFlight;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 鐠囧嫯顔戞禒鎾冲亶 MyBatis 鐎圭偟骞囬妴?
 *
 * @author rr
 * @author codex
 * @since 2026-01-14
 */
@Repository
@RequiredArgsConstructor
public class CommentRepository implements ICommentRepository {

    private static final String KEY_COMMENT_VIEW_PREFIX = "comment:view:";
    private static final String KEY_REPLY_PREVIEW_PREFIX = "comment:reply:preview:";
    private static final String NULL_VALUE = "NULL";
    private static final long L2_TTL_MS = 5_000L;
    private static final long L2_NEG_TTL_MS = 2_000L;
    private static final long JITTER_MS = 500L;

    private final ICommentDao commentDao;
    private final ICommentContentKvPort commentContentKvPort;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final IObjectCounterService objectCounterService;

    private final Cache<Long, CommentViewVO> commentViewCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(2))
            .build();

    private final Cache<String, List<Long>> replyPreviewIdsCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(2))
            .build();

    private final SingleFlight singleFlight = new SingleFlight();

    /**
     * 执行 getBrief 逻辑。
     *
     * @param commentId 评论 ID。类型：{@link Long}
     * @return 处理结果。类型：{@link CommentBriefVO}
     */
    @Override
    public CommentBriefVO getBrief(Long commentId) {
        if (commentId == null) {
            return null;
        }
        CommentPO po = commentDao.selectBriefById(commentId);
        if (po == null) {
            return null;
        }
        return toBrief(po, loadCounterSnapshots(List.of(po)));
    }

    /**
     * 执行 listByIds 逻辑。
     *
     * @param commentIds commentIds 参数。类型：{@link List}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<CommentViewVO> listByIds(List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return List.of();
        }

        Map<Long, CommentViewVO> resultById = new HashMap<>();
        Set<Long> missSet = new LinkedHashSet<>();
        for (Long id : commentIds) {
            if (id == null) {
                continue;
            }
            CommentViewVO cached = commentViewCache.getIfPresent(id);
            if (cached != null) {
                resultById.put(id, copy(cached));
                continue;
            }
            missSet.add(id);
        }
        if (!missSet.isEmpty()) {
            List<Long> missIds = new ArrayList<>(missSet);
            resultById.putAll(readCommentViewsFromRedis(missIds, true));

            List<Long> stillMiss = new ArrayList<>();
            for (Long id : missIds) {
                if (id != null && !resultById.containsKey(id)) {
                    stillMiss.add(id);
                }
            }
            if (!stillMiss.isEmpty()) {
                resultById.putAll(singleFlight.execute(normalizeInflightKey(stillMiss), () -> rebuildCommentViews(stillMiss)));
            }
        }
        return orderCommentViews(commentIds, resultById);
    }

    /**
     * 执行 insert 逻辑。
     *
     * @param commentId 评论 ID。类型：{@link Long}
     * @param postId 帖子 ID。类型：{@link Long}
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param rootId rootId 参数。类型：{@link Long}
     * @param parentId 父评论 ID。类型：{@link Long}
     * @param replyToId replyToId 参数。类型：{@link Long}
     * @param content 文本内容。类型：{@link String}
     * @param status status 参数。类型：{@link Integer}
     * @param nowMs nowMs 参数。类型：{@link Long}
     */
    @Override
    public void insert(Long commentId, Long postId, Long userId, Long rootId, Long parentId, Long replyToId, String content, Integer status, Long nowMs) {
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);

        String contentId = UUID.randomUUID().toString();
        String ym = yearMonth(now);
        // Store body to KV first; both tables are in the same MySQL and share the outer transaction.
        commentContentKvPort.batchAdd(List.of(CommentContentItemVO.builder()
                .postId(postId)
                .yearMonth(ym)
                .contentId(contentId)
                .content(content == null ? "" : content)
                .build()));

        CommentPO po = new CommentPO();
        po.setCommentId(commentId);
        po.setPostId(postId);
        po.setUserId(userId);
        po.setRootId(rootId);
        po.setParentId(parentId);
        po.setReplyToId(replyToId);
        po.setContentId(contentId);
        po.setStatus(status == null ? 1 : status);
        po.setLikeCount(0L);
        po.setCreateTime(now);
        po.setUpdateTime(now);
        commentDao.insert(po);
        evictCommentView(commentId);
        if (rootId != null) {
            evictReplyPreviews(rootId);
        }
    }

    /**
     * 执行 approvePending 逻辑。
     *
     * @param commentId 评论 ID。类型：{@link Long}
     * @param nowMs nowMs 参数。类型：{@link Long}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean approvePending(Long commentId, Long nowMs) {
        if (commentId == null) {
            return false;
        }
        CommentBriefVO brief = getBrief(commentId);
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        boolean updated = commentDao.approvePending(commentId, now) > 0;
        if (updated) {
            evictCommentView(commentId);
            if (brief != null && brief.getRootId() != null) {
                evictReplyPreviews(brief.getRootId());
            }
        }
        return updated;
    }

    /**
     * 执行 rejectPending 逻辑。
     *
     * @param commentId 评论 ID。类型：{@link Long}
     * @param nowMs nowMs 参数。类型：{@link Long}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean rejectPending(Long commentId, Long nowMs) {
        if (commentId == null) {
            return false;
        }
        CommentBriefVO brief = getBrief(commentId);
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        boolean updated = commentDao.rejectPending(commentId, now) > 0;
        if (updated) {
            evictCommentView(commentId);
            if (brief != null && brief.getRootId() != null) {
                evictReplyPreviews(brief.getRootId());
            }
        }
        return updated;
    }

    /**
     * 执行 softDelete 逻辑。
     *
     * @param commentId 评论 ID。类型：{@link Long}
     * @param nowMs nowMs 参数。类型：{@link Long}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean softDelete(Long commentId, Long nowMs) {
        if (commentId == null) {
            return false;
        }
        CommentBriefVO brief = getBrief(commentId);
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        int affected = commentDao.softDelete(commentId, now);
        if (affected > 0) {
            evictCommentView(commentId);
            if (brief != null && brief.getRootId() != null) {
                evictReplyPreviews(brief.getRootId());
            }
        }
        return affected > 0;
    }

    /**
     * 执行 softDeleteByRootId 逻辑。
     *
     * @param rootId rootId 参数。类型：{@link Long}
     * @param nowMs nowMs 参数。类型：{@link Long}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean softDeleteByRootId(Long rootId, Long nowMs) {
        if (rootId == null) {
            return false;
        }
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        int affected = commentDao.softDeleteByRootId(rootId, now);
        if (affected > 0) {
            evictCommentView(rootId);
            evictReplyPreviews(rootId);
        }
        return affected > 0;
    }

    /**
     * 执行 deleteSoftDeletedBefore 逻辑。
     *
     * @param cutoff cutoff 参数。类型：{@link Date}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@code int}
     */
    @Override
    public int deleteSoftDeletedBefore(Date cutoff, int limit) {
        if (cutoff == null) {
            return 0;
        }
        int normalizedLimit = Math.max(1, limit);
        return commentDao.deleteSoftDeletedBefore(cutoff, normalizedLimit);
    }

    /**
     * 执行 pageRootCommentIds 逻辑。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     * @param pinnedId pinnedId 参数。类型：{@link Long}
     * @param cursor 分页游标。类型：{@link String}
     * @param limit 分页大小。类型：{@code int}
     * @param viewerId viewerId 参数。类型：{@link Long}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<Long> pageRootCommentIds(Long postId, Long pinnedId, String cursor, int limit, Long viewerId) {
        Cursor c = Cursor.parse(cursor);
        int normalizedLimit = Math.max(1, limit);
        if (viewerId == null) {
            return commentDao.pageRootIds(postId,
                    pinnedId,
                    c == null ? null : c.cursorTime,
                    c == null ? null : c.cursorId,
                    normalizedLimit);
        }
        return commentDao.pageRootIdsForViewer(postId,
                pinnedId,
                viewerId,
                c == null ? null : c.cursorTime,
                c == null ? null : c.cursorId,
                normalizedLimit);
    }

    /**
     * 执行 pageReplyCommentIds 逻辑。
     *
     * @param rootId rootId 参数。类型：{@link Long}
     * @param cursor 分页游标。类型：{@link String}
     * @param limit 分页大小。类型：{@code int}
     * @param viewerId viewerId 参数。类型：{@link Long}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<Long> pageReplyCommentIds(Long rootId, String cursor, int limit, Long viewerId) {
        Cursor c = Cursor.parse(cursor);
        int normalizedLimit = Math.max(1, limit);
        // preview cache 閸欘亜鍘戠拋鍝ユ暏娴滃骸鍙曢崗閬嶎浕鐏炲繐鐨い纰夌幢鐢?viewerId 閻ㄥ嫮绮ㄩ弸婊€绗夐懗鑺ヨ穿鏉╂稑鍙℃禍?key閵?
        boolean preview = viewerId == null && (cursor == null || cursor.isBlank()) && normalizedLimit <= 10;
        if (preview && rootId != null) {
            String l1Key = rootId + ":" + normalizedLimit;
            List<Long> cached = replyPreviewIdsCache.getIfPresent(l1Key);
            if (cached != null) {
                return new ArrayList<>(cached);
            }

            String redisKey = replyPreviewRedisKey(rootId, normalizedLimit);
            String json = stringRedisTemplate.opsForValue().get(redisKey);
            if (json != null) {
                List<Long> ids = parseReplyPreviewIdsCache(json);
                if (ids != null) {
                    replyPreviewIdsCache.put(l1Key, ids);
                    return new ArrayList<>(ids);
                }
                deleteRedisQuietly(redisKey);
            }

            List<Long> ids = pageReplyIdsFromDb(rootId, c, normalizedLimit, viewerId);
            List<Long> cleaned = cleanIds(ids, normalizedLimit);
            replyPreviewIdsCache.put(l1Key, cleaned);
            writeReplyPreviewIdsCache(redisKey, cleaned);
            return new ArrayList<>(cleaned);
        }

        return pageReplyIdsFromDb(rootId, c, normalizedLimit, viewerId);
    }

    @Override
    public Map<Long, List<Long>> batchListReplyPreviewIds(List<Long> rootIds, int limit, Long viewerId) {
        if (rootIds == null || rootIds.isEmpty()) {
            return Map.of();
        }

        int normalizedLimit = Math.max(1, limit);
        List<Long> deduped = new ArrayList<>(rootIds.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (Long rootId : rootIds) {
            if (rootId != null && seen.add(rootId)) {
                deduped.add(rootId);
            }
        }
        if (deduped.isEmpty()) {
            return Map.of();
        }

        // 只对“匿名 + 小 limit”的预览做缓存；带 viewerId 的预览不能复用缓存，否则会有可见性风险。
        boolean preview = viewerId == null && normalizedLimit <= 10;
        Map<Long, List<Long>> result = new HashMap<>(deduped.size() * 2);

        List<Long> unresolved = new ArrayList<>();
        if (preview) {
            List<Long> l1Miss = new ArrayList<>();
            for (Long rootId : deduped) {
                String l1Key = rootId + ":" + normalizedLimit;
                List<Long> cached = replyPreviewIdsCache.getIfPresent(l1Key);
                if (cached != null) {
                    result.put(rootId, new ArrayList<>(cached));
                    continue;
                }
                l1Miss.add(rootId);
            }

            if (!l1Miss.isEmpty()) {
                List<String> keys = new ArrayList<>(l1Miss.size());
                for (Long rootId : l1Miss) {
                    keys.add(replyPreviewRedisKey(rootId, normalizedLimit));
                }

                List<String> values = null;
                try {
                    values = stringRedisTemplate.opsForValue().multiGet(keys);
                } catch (Exception ignored) {
                    // ignore and fall back to DB
                }

                for (int i = 0; i < l1Miss.size(); i++) {
                    Long rootId = l1Miss.get(i);
                    String redisKey = keys.get(i);
                    String json = values == null || values.size() <= i ? null : values.get(i);
                    if (json != null) {
                        List<Long> ids = parseReplyPreviewIdsCache(json);
                        if (ids != null) {
                            replyPreviewIdsCache.put(rootId + ":" + normalizedLimit, ids);
                            result.put(rootId, new ArrayList<>(ids));
                            continue;
                        }
                        deleteRedisQuietly(redisKey);
                    }
                    unresolved.add(rootId);
                }
            }
        } else {
            unresolved.addAll(deduped);
        }

        if (!unresolved.isEmpty()) {
            List<CommentPO> rows = commentDao.selectReplyPreviewIdsByRootIds(unresolved, viewerId, normalizedLimit);
            Map<Long, List<Long>> idsByRoot = new HashMap<>(unresolved.size() * 2);
            if (rows != null) {
                for (CommentPO po : rows) {
                    if (po == null || po.getRootId() == null || po.getCommentId() == null) {
                        continue;
                    }
                    idsByRoot.computeIfAbsent(po.getRootId(), x -> new ArrayList<>()).add(po.getCommentId());
                }
            }

            for (Long rootId : unresolved) {
                List<Long> ids = cleanIds(idsByRoot.get(rootId), normalizedLimit);
                if (preview) {
                    String l1Key = rootId + ":" + normalizedLimit;
                    replyPreviewIdsCache.put(l1Key, ids);
                    writeReplyPreviewIdsCache(replyPreviewRedisKey(rootId, normalizedLimit), ids);
                }
                result.put(rootId, new ArrayList<>(ids));
            }
        }

        // 保证每个 rootId 都有返回值，调用方可以直接 getOrDefault。
        for (Long rootId : deduped) {
            result.putIfAbsent(rootId, List.of());
        }
        return result;
    }

    private List<Long> pageReplyIdsFromDb(Long rootId, Cursor c, int normalizedLimit, Long viewerId) {
        if (viewerId == null) {
            return commentDao.pageReplyIds(rootId,
                    c == null ? null : c.cursorTime,
                    c == null ? null : c.cursorId,
                    normalizedLimit);
        }
        return commentDao.pageReplyIdsForViewer(rootId,
                viewerId,
                c == null ? null : c.cursorTime,
                c == null ? null : c.cursorId,
                normalizedLimit);
    }

    private List<CommentViewVO> orderCommentViews(List<Long> commentIds, Map<Long, CommentViewVO> resultById) {
        List<CommentViewVO> ordered = new ArrayList<>(commentIds == null ? 0 : commentIds.size());
        if (commentIds == null || commentIds.isEmpty()) {
            return ordered;
        }
        for (Long id : commentIds) {
            CommentViewVO view = resultById.get(id);
            if (view != null) {
                ordered.add(copy(view));
            }
        }
        return ordered;
    }

    private Map<Long, CommentViewVO> rebuildCommentViews(List<Long> commentIds) {
        Map<Long, CommentViewVO> result = readCommentViewsFromRedis(commentIds, true);
        List<Long> unresolved = new ArrayList<>();
        for (Long id : commentIds) {
            if (id != null && !result.containsKey(id)) {
                unresolved.add(id);
            }
        }
        if (unresolved.isEmpty()) {
            return result;
        }

        List<CommentPO> list = commentDao.selectByIds(unresolved);
        Map<Long, CounterSnapshot> counterByCommentId = loadCounterSnapshots(list);
        Map<Long, CommentViewVO> found = new HashMap<>();
        List<ContentKey> contentKeys = new ArrayList<>();
        if (list != null) {
            for (CommentPO po : list) {
                CommentViewVO view = toView(po, counterByCommentId);
                if (view == null || view.getCommentId() == null) {
                    continue;
                }
                found.put(view.getCommentId(), view);
                contentKeys.add(ContentKey.from(po));
            }
        }
        fillContentsFromKv(found, contentKeys);

        for (Long id : unresolved) {
            if (id == null) {
                continue;
            }
            CommentViewVO view = found.get(id);
            if (view == null) {
                writeNullCache(commentViewRedisKey(id));
                continue;
            }
            CommentViewVO snapshot = sanitizeSnapshot(view);
            commentViewCache.put(id, snapshot);
            result.put(id, copy(snapshot));
            writeCommentViewCache(id, snapshot);
        }
        return result;
    }

    private Map<Long, CommentViewVO> readCommentViewsFromRedis(List<Long> commentIds, boolean deleteBrokenKey) {
        Map<Long, CommentViewVO> result = new HashMap<>();
        if (commentIds == null || commentIds.isEmpty()) {
            return result;
        }
        List<String> keys = new ArrayList<>(commentIds.size());
        for (Long id : commentIds) {
            if (id != null) {
                keys.add(commentViewRedisKey(id));
            }
        }
        if (keys.isEmpty()) {
            return result;
        }
        List<String> jsons;
        try {
            jsons = stringRedisTemplate.opsForValue().multiGet(keys);
        } catch (Exception ignored) {
            return result;
        }
        if (jsons == null || jsons.size() != keys.size()) {
            return result;
        }
        int idx = 0;
        for (Long id : commentIds) {
            if (id == null) {
                continue;
            }
            String json = jsons.get(idx++);
            if (json == null) {
                continue;
            }
            if (NULL_VALUE.equals(json)) {
                // 负缓存命中也算“已解析完成”，必须阻止后续再次回表。
                result.put(id, null);
                continue;
            }
            CommentViewVO parsed = parseCommentViewCache(json);
            if (parsed == null || parsed.getCommentId() == null) {
                if (deleteBrokenKey) {
                    deleteRedisQuietly(commentViewRedisKey(id));
                }
                continue;
            }
            CommentViewVO snapshot = sanitizeSnapshot(parsed);
            commentViewCache.put(id, snapshot);
            result.put(id, copy(snapshot));
        }
        return result;
    }

    private String normalizeInflightKey(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        List<Long> normalized = new ArrayList<>();
        for (Long id : ids) {
            if (id != null && !normalized.contains(id)) {
                normalized.add(id);
            }
        }
        normalized.sort(Long::compareTo);
        List<String> parts = new ArrayList<>(normalized.size());
        for (Long id : normalized) {
            parts.add(String.valueOf(id));
        }
        return String.join(",", parts);
    }

    private void evictCommentView(Long commentId) {
        if (commentId == null) {
            return;
        }
        commentViewCache.invalidate(commentId);
        deleteRedisQuietly(commentViewRedisKey(commentId));
    }

    private void evictReplyPreviews(Long rootId) {
        if (rootId == null) {
            return;
        }
        for (int limit = 1; limit <= 10; limit++) {
            replyPreviewIdsCache.invalidate(rootId + ":" + limit);
            deleteRedisQuietly(replyPreviewRedisKey(rootId, limit));
        }
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
    private String commentViewRedisKey(Long commentId) {
        if (commentId == null) {
            return KEY_COMMENT_VIEW_PREFIX;
        }
        return KEY_COMMENT_VIEW_PREFIX + commentId;
    }

    private String replyPreviewRedisKey(Long rootId, int limit) {
        if (rootId == null) {
            return KEY_REPLY_PREVIEW_PREFIX;
        }
        return KEY_REPLY_PREVIEW_PREFIX + rootId + ":" + limit;
    }

    private Duration ttl(long baseMs) {
        long jitter = ThreadLocalRandom.current().nextLong(0L, JITTER_MS + 1);
        return Duration.ofMillis(baseMs + jitter);
    }

    private CommentViewVO toView(CommentPO po) {
        return toView(po, Map.of());
    }

    private CommentViewVO toView(CommentPO po, Map<Long, CounterSnapshot> counterByCommentId) {
        if (po == null) {
            return null;
        }
        Date ct = po.getCreateTime();
        return CommentViewVO.builder()
                .commentId(po.getCommentId())
                .postId(po.getPostId())
                .userId(po.getUserId())
                .rootId(po.getRootId())
                .parentId(po.getParentId())
                .replyToId(po.getReplyToId())
                .content(null)
                .status(po.getStatus())
                .likeCount(counterSnapshotOf(po, counterByCommentId).likeCount())
                .createTime(ct == null ? null : ct.getTime())
                .build();
    }

    private void fillContentsFromKv(Map<Long, CommentViewVO> found, List<ContentKey> keys) {
        if (found == null || found.isEmpty() || keys == null || keys.isEmpty()) {
            return;
        }

        Map<Long, List<ContentKey>> byPost = new HashMap<>();
        for (ContentKey k : keys) {
            if (k == null || k.commentId == null || k.postId == null) {
                continue;
            }
            if (k.contentId == null || k.contentId.isBlank()) {
                continue;
            }
            String ym = yearMonth(k.createTime);
            if (ym == null) {
                continue;
            }
            byPost.computeIfAbsent(k.postId, x -> new ArrayList<>()).add(new ContentKey(k.commentId, k.postId, k.contentId, k.createTime, ym));
        }

        if (byPost.isEmpty()) {
            return;
        }

        for (Map.Entry<Long, List<ContentKey>> en : byPost.entrySet()) {
            Long postId = en.getKey();
            List<ContentKey> list = en.getValue();
            if (postId == null || list == null || list.isEmpty()) {
                continue;
            }

            List<CommentContentKeyVO> req = new ArrayList<>(list.size());
            for (ContentKey k : list) {
                if (k == null || k.yearMonth == null || k.contentId == null) {
                    continue;
                }
                req.add(CommentContentKeyVO.builder().yearMonth(k.yearMonth).contentId(k.contentId).build());
            }
            if (req.isEmpty()) {
                continue;
            }

            Map<String, String> contentById = new HashMap<>();
            try {
                List<CommentContentResultVO> rows = commentContentKvPort.batchFind(postId, req);
                if (rows != null) {
                    for (CommentContentResultVO r : rows) {
                        if (r == null || r.getContentId() == null) {
                            continue;
                        }
                        contentById.put(r.getContentId(), r.getContent());
                    }
                }
            } catch (Exception e) {
                // KV unavailable: keep content null/empty, do not break comment list.
                continue;
            }

            for (ContentKey k : list) {
                if (k == null || k.commentId == null) {
                    continue;
                }
                CommentViewVO vo = found.get(k.commentId);
                if (vo == null) {
                    continue;
                }
                String c = contentById.get(k.contentId);
                vo.setContent(c == null ? "" : c);
            }
        }
    }

    private String yearMonth(Date time) {
        if (time == null) {
            return null;
        }
        try {
            Instant instant = time.toInstant();
            LocalDateTime dt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"));
            int m = dt.getMonthValue();
            return dt.getYear() + "-" + (m < 10 ? ("0" + m) : String.valueOf(m));
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ContentKey(Long commentId, Long postId, String contentId, Date createTime, String yearMonth) {
        private static ContentKey from(CommentPO po) {
            if (po == null) {
                return null;
            }
            return new ContentKey(po.getCommentId(), po.getPostId(), po.getContentId(), po.getCreateTime(), null);
        }
    }

    private CommentViewVO copy(CommentViewVO v) {
        if (v == null) {
            return null;
        }
        return CommentViewVO.builder()
                .commentId(v.getCommentId())
                .postId(v.getPostId())
                .userId(v.getUserId())
                .nickname(v.getNickname())
                .avatarUrl(v.getAvatarUrl())
                .rootId(v.getRootId())
                .parentId(v.getParentId())
                .replyToId(v.getReplyToId())
                .content(v.getContent())
                .status(v.getStatus())
                .likeCount(v.getLikeCount())
                .createTime(v.getCreateTime())
                .build();
    }

    private CommentViewVO sanitizeSnapshot(CommentViewVO v) {
        if (v == null) {
            return null;
        }
        // 缂傛挸鐡ㄩ柌灞肩瑝鐎?nickname/avatar閿涘矂浼╅崗宥堟硶鐠囬攱鐪板Ч鈩冪厠閿涙稖顕版笟褏鏁?IUserBaseRepository 鐞涖儱鍙忛妴?
        CommentViewVO snap = copy(v);
        if (snap != null) {
            snap.setNickname(null);
            snap.setAvatarUrl(null);
        }
        return snap;
    }

    private CommentViewVO parseCommentViewCache(String json) {
        if (json == null || json.isBlank() || NULL_VALUE.equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CommentViewVO.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeCommentViewCache(Long commentId, CommentViewVO vo) {
        if (commentId == null || vo == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(vo);
            stringRedisTemplate.opsForValue().set(commentViewRedisKey(commentId), json, ttl(L2_TTL_MS));
        } catch (Exception ignored) {
        }
    }

    private void writeNullCache(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, NULL_VALUE, ttl(L2_NEG_TTL_MS));
        } catch (Exception ignored) {
        }
    }

    private List<Long> parseReplyPreviewIdsCache(String json) {
        if (json == null || json.isBlank() || NULL_VALUE.equals(json)) {
            return null;
        }
        try {
            List<?> raw = objectMapper.readValue(json, List.class);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<Long> res = new ArrayList<>(raw.size());
            for (Object o : raw) {
                if (o instanceof Number n) {
                    res.add(n.longValue());
                    continue;
                }
                if (o instanceof String s) {
                    try {
                        res.add(Long.parseLong(s));
                    } catch (Exception ignored) {
                    }
                }
            }
            return res;
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Long> cleanIds(List<Long> ids, int limit) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        int l = Math.max(1, limit);
        List<Long> res = new ArrayList<>(Math.min(ids.size(), l));
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            res.add(id);
            if (res.size() >= l) {
                break;
            }
        }
        return res;
    }

    private void writeReplyPreviewIdsCache(String redisKey, List<Long> ids) {
        if (redisKey == null || redisKey.isBlank() || ids == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(ids);
            stringRedisTemplate.opsForValue().set(redisKey, json, ttl(L2_TTL_MS));
        } catch (Exception ignored) {
        }
    }

    /**
     * 执行 listRecentRootBriefs 逻辑。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<CommentBriefVO> listRecentRootBriefs(Long postId, int limit) {
        if (postId == null) {
            return List.of();
        }
        int normalizedLimit = Math.max(1, limit);
        List<CommentPO> list = commentDao.selectRecentRootBriefs(postId, normalizedLimit);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Map<Long, CounterSnapshot> counterByCommentId = loadCounterSnapshots(list);
        List<CommentBriefVO> res = new ArrayList<>(list.size());
        for (CommentPO po : list) {
            if (po == null) {
                continue;
            }
            res.add(toBrief(po, counterByCommentId));
        }
        return res;
    }

    private CommentBriefVO toBrief(CommentPO po, Map<Long, CounterSnapshot> counterByCommentId) {
        if (po == null) {
            return null;
        }
        return CommentBriefVO.builder()
                .commentId(po.getCommentId())
                .postId(po.getPostId())
                .userId(po.getUserId())
                .rootId(po.getRootId())
                .status(po.getStatus())
                .likeCount(counterSnapshotOf(po, counterByCommentId).likeCount())
                .build();
    }

    private Map<Long, CounterSnapshot> loadCounterSnapshots(List<CommentPO> comments) {
        if (comments == null || comments.isEmpty()) {
            return Map.of();
        }
        List<Long> commentIds = new ArrayList<>(comments.size());
        Map<Long, CounterSnapshot> fallback = new HashMap<>(comments.size() * 2);
        for (CommentPO po : comments) {
            if (po == null || po.getCommentId() == null) {
                continue;
            }
            Long commentId = po.getCommentId();
            fallback.put(commentId, new CounterSnapshot(safeLong(po.getLikeCount())));
            commentIds.add(commentId);
        }
        if (commentIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, Map<String, Long>> likeCountByCommentId = objectCounterService.getCountsBatch(
                    ReactionTargetTypeEnumVO.COMMENT,
                    commentIds,
                    List.of(ObjectCounterType.LIKE));
            Map<Long, CounterSnapshot> result = new HashMap<>(fallback.size() * 2);
            for (CommentPO po : comments) {
                if (po == null || po.getCommentId() == null) {
                    continue;
                }
                Long commentId = po.getCommentId();
                CounterSnapshot defaultCounter = fallback.get(commentId);
                long fallbackLike = defaultCounter == null ? 0L : defaultCounter.likeCount();
                Map<String, Long> likeCounts = likeCountByCommentId == null ? null : likeCountByCommentId.get(commentId);
                Long like = likeCounts == null ? null : likeCounts.get(ObjectCounterType.LIKE.getCode());
                long likeCount = like == null ? fallbackLike : safeLong(like);
                result.put(commentId, new CounterSnapshot(likeCount));
            }
            return result;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private CounterSnapshot counterSnapshotOf(CommentPO po, Map<Long, CounterSnapshot> counterByCommentId) {
        if (po == null || po.getCommentId() == null) {
            return CounterSnapshot.ZERO;
        }
        CounterSnapshot counter = counterByCommentId == null ? null : counterByCommentId.get(po.getCommentId());
        if (counter != null) {
            return counter;
        }
        return new CounterSnapshot(safeLong(po.getLikeCount()));
    }

    private long safeLong(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private record CounterSnapshot(Long likeCount) {
        private static final CounterSnapshot ZERO = new CounterSnapshot(0L);
    }

    private static final class Cursor {
        private final Date cursorTime;
        private final Long cursorId;

        private Cursor(Date cursorTime, Long cursorId) {
            this.cursorTime = cursorTime;
            this.cursorId = cursorId;
        }

        private static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            String[] parts = cursor.split(":");
            if (parts.length != 2) {
                return null;
            }
            try {
                long timeMs = Long.parseLong(parts[0]);
                long id = Long.parseLong(parts[1]);
                return new Cursor(new Date(timeMs), id);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
