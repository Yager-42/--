package cn.nexus.config;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.infrastructure.dao.social.IContentPostDao;
import cn.nexus.infrastructure.dao.social.IContentPostTypeDao;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.infrastructure.dao.social.po.ContentPostTypePO;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import cn.nexus.types.enums.ContentMediaTypeEnumVO;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Search 索引回灌 Runner：首次上线/重建索引用（默认关闭，可显式开启）。
 *
 * <p>特点：游标分页 + checkpoint 断点续跑 + best-effort（单条失败只 warn）。</p>
 *
 * @author codex
 * @since 2026-02-02
 */
@Component
public class SearchIndexBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexBackfillRunner.class);

    @Value("${search.backfill.enabled:false}")
    private boolean enabled;

    @Value("${search.backfill.pageSize:500}")
    private int pageSize;

    @Value("${search.backfill.checkpoint.enabled:true}")
    private boolean checkpointEnabled;

    @Value("${search.backfill.checkpoint.redisKey:search:backfill:cursor}")
    private String checkpointKey;

    private final IContentPostDao contentPostDao;
    private final IContentPostTypeDao contentPostTypeDao;
    private final IUserBaseDao userBaseDao;
    private final ISearchEnginePort searchEnginePort;
    private final StringRedisTemplate stringRedisTemplate;

    public SearchIndexBackfillRunner(IContentPostDao contentPostDao,
                                    IContentPostTypeDao contentPostTypeDao,
                                    IUserBaseDao userBaseDao,
                                    ISearchEnginePort searchEnginePort,
                                    StringRedisTemplate stringRedisTemplate) {
        this.contentPostDao = contentPostDao;
        this.contentPostTypeDao = contentPostTypeDao;
        this.userBaseDao = userBaseDao;
        this.searchEnginePort = searchEnginePort;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        int limit = Math.max(1, pageSize);
        log.warn("search index backfill runner enabled, pageSize={}", limit);

        Cursor cursor = checkpointEnabled ? loadCheckpoint() : Cursor.empty();
        Date cursorTime = cursor.cursorTimeMs == null ? null : new Date(cursor.cursorTimeMs);
        Long cursorPostId = cursor.cursorPostId;

        long total = 0L;
        while (true) {
            List<ContentPostPO> page = contentPostDao.selectPublishedPage(cursorTime, cursorPostId, limit);
            if (page == null || page.isEmpty()) {
                break;
            }

            List<Long> postIds = new ArrayList<>(page.size());
            List<Long> userIds = new ArrayList<>(page.size());
            ContentPostPO last = null;
            for (ContentPostPO po : page) {
                if (po == null || po.getPostId() == null) {
                    continue;
                }
                postIds.add(po.getPostId());
                if (po.getUserId() != null && !userIds.contains(po.getUserId())) {
                    userIds.add(po.getUserId());
                }
                last = po;
            }
            if (last == null || last.getPostId() == null || last.getCreateTime() == null) {
                break;
            }

            Map<Long, List<String>> typesByPostId = loadPostTypes(postIds);
            Map<Long, String> nicknameByUserId = loadNicknames(userIds);

            for (ContentPostPO po : page) {
                if (po == null || po.getPostId() == null) {
                    continue;
                }
                Long postId = po.getPostId();
                String docId = docId(postId);
                long itemStartNs = System.nanoTime();

                try {
                    if (!shouldIndex(po)) {
                        searchEnginePort.delete(docId);
                        log.info("event=search.index.delete docId={} postId={} reason=NOT_INDEXABLE costMs={}",
                                docId, postId, costMs(itemStartNs));
                        total++;
                        continue;
                    }

                    long createTimeMs = po.getCreateTime() == null ? System.currentTimeMillis() : po.getCreateTime().getTime();
                    Integer mediaType = po.getMediaType() == null ? ContentMediaTypeEnumVO.TEXT.getCode() : po.getMediaType();
                    String nickname = nicknameByUserId.getOrDefault(po.getUserId(), "");

                    SearchDocumentVO doc = SearchDocumentVO.builder()
                            .entityIdStr(String.valueOf(postId))
                            .createTimeMs(createTimeMs)
                            .postId(postId)
                            .authorId(po.getUserId())
                            .authorNickname(nickname)
                            .contentText(po.getContentText() == null ? "" : po.getContentText())
                            .postTypes(normalizePostTypes(typesByPostId.get(postId)))
                            .mediaType(mediaType)
                            .build();
                    searchEnginePort.upsert(doc);
                    log.info("event=search.index.upsert docId={} postId={} costMs={}", docId, postId, costMs(itemStartNs));
                } catch (Exception e) {
                    // best-effort：失败不阻断回灌，可后续重跑（幂等 docId）。
                    log.warn("search backfill upsert failed, postId={}", postId, e);
                }
                total++;
            }

            cursorTime = last.getCreateTime();
            cursorPostId = last.getPostId();
            if (checkpointEnabled) {
                saveCheckpoint(checkpointKey, cursorTime.getTime(), cursorPostId);
            }

            log.warn("search index backfill progress, total={}, cursorPostId={}, cursorTimeMs={}",
                    total, cursorPostId, cursorTime.getTime());

            if (page.size() < limit) {
                break;
            }
        }

        if (checkpointEnabled) {
            stringRedisTemplate.delete(checkpointKey);
        }
        log.warn("search index backfill runner finished, total={}", total);
    }

    private boolean shouldIndex(ContentPostPO po) {
        if (po == null) {
            return false;
        }
        Integer status = po.getStatus();
        if (status == null || status != ContentPostStatusEnumVO.PUBLISHED.getCode()) {
            return false;
        }
        Integer visibility = po.getVisibility();
        return visibility != null && visibility == ContentPostVisibilityEnumVO.PUBLIC.getCode();
    }

    private Map<Long, List<String>> loadPostTypes(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        List<ContentPostTypePO> rows = contentPostTypeDao.selectByPostIds(postIds);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> map = new HashMap<>();
        for (ContentPostTypePO row : rows) {
            if (row == null || row.getPostId() == null || row.getType() == null) {
                continue;
            }
            map.computeIfAbsent(row.getPostId(), k -> new ArrayList<>()).add(row.getType());
        }
        return map;
    }

    private Map<Long, String> loadNicknames(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<UserBasePO> rows = userBaseDao.selectByUserIds(userIds);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new HashMap<>();
        for (UserBasePO row : rows) {
            if (row == null || row.getUserId() == null) {
                continue;
            }
            // 迁移期兼容：优先使用展示昵称 nickname；若为空则回退到 username（handle），避免回灌写出空 authorNickname。
            String nickname = row.getNickname();
            if (nickname == null || nickname.isBlank()) {
                nickname = row.getUsername();
            }
            map.put(row.getUserId(), nickname == null ? "" : nickname);
        }
        return map;
    }

    private List<String> normalizePostTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> res = new ArrayList<>(Math.min(5, raw.size()));
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String v = s.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (res.contains(v)) {
                continue;
            }
            res.add(v);
            if (res.size() >= 5) {
                break;
            }
        }
        return res;
    }

    private Cursor loadCheckpoint() {
        try {
            String raw = stringRedisTemplate.opsForValue().get(checkpointKey);
            return Cursor.parse(raw);
        } catch (Exception e) {
            log.warn("search backfill checkpoint load failed, key={}", checkpointKey, e);
            return Cursor.empty();
        }
    }

    private void saveCheckpoint(String key, long lastCreateTimeMs, long lastPostId) {
        try {
            stringRedisTemplate.opsForValue().set(key, lastCreateTimeMs + ":" + lastPostId);
        } catch (Exception e) {
            log.warn("search backfill checkpoint save failed, key={}, cursor={}:{}", key, lastCreateTimeMs, lastPostId, e);
        }
    }

    private String docId(Long postId) {
        return "POST:" + postId;
    }

    private long costMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    private static final class Cursor {
        private final Long cursorTimeMs;
        private final Long cursorPostId;

        private Cursor(Long cursorTimeMs, Long cursorPostId) {
            this.cursorTimeMs = cursorTimeMs;
            this.cursorPostId = cursorPostId;
        }

        private static Cursor empty() {
            return new Cursor(null, null);
        }

        private static Cursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return empty();
            }
            String s = raw.trim();
            int idx = s.indexOf(':');
            if (idx <= 0 || idx >= s.length() - 1) {
                return empty();
            }
            try {
                long t = Long.parseLong(s.substring(0, idx));
                long id = Long.parseLong(s.substring(idx + 1));
                return new Cursor(t, id);
            } catch (NumberFormatException ignored) {
                return empty();
            }
        }
    }
}
