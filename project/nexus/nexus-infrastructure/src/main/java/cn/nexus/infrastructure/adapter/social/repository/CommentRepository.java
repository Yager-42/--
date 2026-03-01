package cn.nexus.infrastructure.adapter.social.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.CommentViewVO;
import cn.nexus.infrastructure.dao.social.ICommentDao;
import cn.nexus.infrastructure.dao.social.po.CommentPO;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 评论仓储 MyBatis 实现。
 *
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
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private final Cache<Long, CommentViewVO> commentViewCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(2))
            .build();

    private final Cache<String, List<Long>> replyPreviewIdsCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(2))
            .build();

    @Override
    public CommentBriefVO getBrief(Long commentId) {
        if (commentId == null) {
            return null;
        }
        CommentPO po = commentDao.selectBriefById(commentId);
        if (po == null) {
            return null;
        }
        return CommentBriefVO.builder()
                .commentId(po.getCommentId())
                .postId(po.getPostId())
                .userId(po.getUserId())
                .rootId(po.getRootId())
                .status(po.getStatus())
                .likeCount(po.getLikeCount())
                .replyCount(po.getReplyCount())
                .build();
    }

    @Override
    public List<CommentViewVO> listByIds(List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return List.of();
        }
        // L1：Caffeine 命中；必须返回 copy，避免读侧 enrichUserProfile 修改 nickname/avatar 污染缓存对象。
        List<CommentViewVO> res = new ArrayList<>();
        Set<Long> missSet = new LinkedHashSet<>();
        for (Long id : commentIds) {
            if (id == null) {
                continue;
            }
            CommentViewVO cached = commentViewCache.getIfPresent(id);
            if (cached != null) {
                res.add(copy(cached));
                continue;
            }
            missSet.add(id);
        }
        if (missSet.isEmpty()) {
            return res;
        }

        // L2：Redis multiGet。
        List<Long> missIds = new ArrayList<>(missSet);
        List<String> keys = new ArrayList<>(missIds.size());
        for (Long id : missIds) {
            keys.add(commentViewRedisKey(id));
        }
        List<String> jsons = stringRedisTemplate.opsForValue().multiGet(keys);
        List<Long> stillMiss = new ArrayList<>();
        if (jsons == null || jsons.size() != missIds.size()) {
            stillMiss.addAll(missIds);
        } else {
            for (int i = 0; i < missIds.size(); i++) {
                Long id = missIds.get(i);
                String json = jsons.get(i);
                if (json == null) {
                    stillMiss.add(id);
                    continue;
                }
                if (NULL_VALUE.equals(json)) {
                    continue;
                }
                CommentViewVO parsed = parseCommentViewCache(json);
                if (parsed == null || parsed.getCommentId() == null) {
                    stillMiss.add(id);
                    continue;
                }
                CommentViewVO snap = sanitizeSnapshot(parsed);
                commentViewCache.put(id, snap);
                res.add(copy(snap));
            }
        }
        if (stillMiss.isEmpty()) {
            return res;
        }

        // DB：回源查 missIds；命中写回 L2(5s+jitter)+回填 L1；miss 写 "NULL"(2s+jitter)。
        List<CommentPO> list = commentDao.selectByIds(stillMiss);
        Map<Long, CommentViewVO> found = new HashMap<>();
        if (list != null) {
            for (CommentPO po : list) {
                CommentViewVO vo = toView(po);
                if (vo != null && vo.getCommentId() != null) {
                    found.put(vo.getCommentId(), vo);
                }
            }
        }

        for (Long id : stillMiss) {
            if (id == null) {
                continue;
            }
            CommentViewVO vo = found.get(id);
            if (vo == null) {
                writeNullCache(commentViewRedisKey(id));
                continue;
            }
            CommentViewVO snap = sanitizeSnapshot(vo);
            commentViewCache.put(id, snap);
            res.add(copy(snap));
            writeCommentViewCache(id, snap);
        }

        return res;
    }

    @Override
    public void insert(Long commentId, Long postId, Long userId, Long rootId, Long parentId, Long replyToId, String content, Integer status, Long nowMs) {
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        CommentPO po = new CommentPO();
        po.setCommentId(commentId);
        po.setPostId(postId);
        po.setUserId(userId);
        po.setRootId(rootId);
        po.setParentId(parentId);
        po.setReplyToId(replyToId);
        po.setContent(content == null ? "" : content);
        po.setStatus(status == null ? 1 : status);
        po.setLikeCount(0L);
        po.setReplyCount(0L);
        po.setCreateTime(now);
        po.setUpdateTime(now);
        commentDao.insert(po);
    }

    @Override
    public boolean approvePending(Long commentId, Long nowMs) {
        if (commentId == null) {
            return false;
        }
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        return commentDao.approvePending(commentId, now) > 0;
    }

    @Override
    public boolean rejectPending(Long commentId, Long nowMs) {
        if (commentId == null) {
            return false;
        }
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        return commentDao.rejectPending(commentId, now) > 0;
    }

    @Override
    public boolean softDelete(Long commentId, Long nowMs) {
        if (commentId == null) {
            return false;
        }
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        int affected = commentDao.softDelete(commentId, now);
        return affected > 0;
    }

    @Override
    public boolean softDeleteByRootId(Long rootId, Long nowMs) {
        if (rootId == null) {
            return false;
        }
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        int affected = commentDao.softDeleteByRootId(rootId, now);
        return affected > 0;
    }

    @Override
    public int deleteSoftDeletedBefore(Date cutoff, int limit) {
        if (cutoff == null) {
            return 0;
        }
        int normalizedLimit = Math.max(1, limit);
        return commentDao.deleteSoftDeletedBefore(cutoff, normalizedLimit);
    }

    @Override
    public void addReplyCount(Long rootCommentId, Long delta) {
        if (rootCommentId == null || delta == null || delta == 0) {
            return;
        }
        commentDao.addReplyCount(rootCommentId, delta);
    }

    @Override
    public void addLikeCount(Long rootCommentId, Long delta) {
        if (rootCommentId == null || delta == null || delta == 0) {
            return;
        }
        commentDao.addLikeCount(rootCommentId, delta);
    }

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

    @Override
    public List<Long> pageReplyCommentIds(Long rootId, String cursor, int limit, Long viewerId) {
        Cursor c = Cursor.parse(cursor);
        int normalizedLimit = Math.max(1, limit);
        boolean preview = (cursor == null || cursor.isBlank()) && normalizedLimit <= 10;
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
            }

            List<Long> ids = pageReplyIdsFromDb(rootId, c, normalizedLimit, viewerId);
            List<Long> cleaned = cleanIds(ids, normalizedLimit);
            replyPreviewIdsCache.put(l1Key, cleaned);
            writeReplyPreviewIdsCache(redisKey, cleaned);
            return new ArrayList<>(cleaned);
        }

        return pageReplyIdsFromDb(rootId, c, normalizedLimit, viewerId);
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
                .content(po.getContent())
                .status(po.getStatus())
                .likeCount(po.getLikeCount())
                .replyCount(po.getReplyCount())
                .createTime(ct == null ? null : ct.getTime())
                .build();
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
                .replyCount(v.getReplyCount())
                .createTime(v.getCreateTime())
                .build();
    }

    private CommentViewVO sanitizeSnapshot(CommentViewVO v) {
        if (v == null) {
            return null;
        }
        // 缓存里不存 nickname/avatar，避免跨请求污染；读侧由 IUserBaseRepository 补全。
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
        List<CommentBriefVO> res = new ArrayList<>(list.size());
        for (CommentPO po : list) {
            if (po == null) {
                continue;
            }
            res.add(CommentBriefVO.builder()
                    .commentId(po.getCommentId())
                    .postId(po.getPostId())
                    .userId(po.getUserId())
                    .rootId(po.getRootId())
                    .status(po.getStatus())
                    .likeCount(po.getLikeCount())
                    .replyCount(po.getReplyCount())
                    .build());
        }
        return res;
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
