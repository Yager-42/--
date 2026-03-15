package cn.nexus.config;

import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.infrastructure.dao.social.IContentPostDao;
import cn.nexus.infrastructure.dao.social.IContentPostTypeDao;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.infrastructure.dao.social.po.ContentPostTypePO;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import cn.nexus.trigger.search.support.SearchDocumentAssembler;

/**
 * SearchIndexBackfillRunner 配置类。
 *
 * @author rr
 * @author codex
 * @since 2026-02-02
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class SearchIndexBackfillRunner implements ApplicationRunner {

    @Value("${search.backfill.enabled:true}")
    private boolean enabled;

    @Value("${search.backfill.pageSize:500}")
    private int pageSize;

    @Value("${search.backfill.checkpoint.enabled:true}")
    private boolean checkpointEnabled;

    @Value("${search.backfill.checkpoint.redisKey:search:backfill:cursor}")
    private String checkpointKey;

    @Value("${search.es.indexAlias:zhiguang_content_index}")
    private String indexAlias;

    private final IContentPostDao contentPostDao;
    private final IContentPostTypeDao contentPostTypeDao;
    private final IUserBaseDao userBaseDao;
    private final IPostContentKvPort postContentKvPort;
    private final IReactionRepository reactionRepository;
    private final ISearchEnginePort searchEnginePort;
    private final SearchDocumentAssembler searchDocumentAssembler;
    private final RestClient searchRestClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行启动任务。
     *
     * @param args args 参数。类型：{@link ApplicationArguments}
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        int limit = Math.max(1, pageSize);
        Cursor cursor = checkpointEnabled ? loadCheckpoint() : Cursor.empty();
        log.info("search index backfill started, index={}, checkpointEnabled={}, cursor={}:{}",
                indexAlias,
                checkpointEnabled,
                cursor.cursorPublishTimeMs,
                cursor.cursorPostId);
        Date cursorPublishTime = cursor.cursorPublishTimeMs == null ? null : new Date(cursor.cursorPublishTimeMs);
        Long cursorPostId = cursor.cursorPostId;
        long total = 0L;

        while (true) {
            List<ContentPostPO> page = contentPostDao.selectPublishedPageForSearch(
                    ContentPostStatusEnumVO.PUBLISHED.getCode(),
                    ContentPostVisibilityEnumVO.PUBLIC.getCode(),
                    cursorPublishTime,
                    cursorPostId,
                    limit);
            if (page == null || page.isEmpty()) {
                break;
            }

            List<Long> postIds = new ArrayList<>(page.size());
            List<Long> userIds = new ArrayList<>(page.size());
            for (ContentPostPO po : page) {
                if (po == null || po.getPostId() == null) {
                    continue;
                }
                postIds.add(po.getPostId());
                if (po.getUserId() != null) {
                    userIds.add(po.getUserId());
                }
            }

            Map<Long, List<String>> typesByPostId = loadPostTypes(postIds);
            Map<Long, UserBasePO> usersById = loadUsers(userIds);
            Map<String, String> contentByUuid = loadContents(page);

            for (ContentPostPO po : page) {
                if (po == null || po.getPostId() == null) {
                    continue;
                }
                if (!indexable(po)) {
                    continue;
                }
                if (po.getTitle() == null || po.getTitle().isBlank()) {
                    log.warn("search backfill skip because title is blank, postId={}", po.getPostId());
                    continue;
                }
                if (po.getPublishTime() == null) {
                    log.warn("search backfill skip because publish_time is null, postId={}", po.getPostId());
                    continue;
                }
                try {
                    searchEnginePort.upsert(buildDocument(po, typesByPostId, usersById, contentByUuid));
                    total++;
                } catch (Exception e) {
                    log.warn("search backfill upsert failed, postId={}", po.getPostId(), e);
                }
            }

            ContentPostPO last = page.get(page.size() - 1);
            cursorPublishTime = last.getPublishTime();
            cursorPostId = last.getPostId();
            if (checkpointEnabled && cursorPublishTime != null && cursorPostId != null) {
                saveCheckpoint(checkpointKey, cursorPublishTime.getTime(), cursorPostId);
            }
            if (page.size() < limit) {
                break;
            }
        }

        if (checkpointEnabled) {
            stringRedisTemplate.delete(checkpointKey);
        }
        log.info("search index backfill finished, total={}", total);
    }

    private long countIndex() {
        try {
            Request request = new Request("GET", "/" + indexAlias + "/_count");
            Response response = searchRestClient.performRequest(request);
            JsonNode root = objectMapper.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
            return root.path("count").asLong(0L);
        } catch (ResponseException e) {
            return 0L;
        } catch (Exception e) {
            log.warn("count search index failed, index={}", indexAlias, e);
            return 0L;
        }
    }

    private boolean indexable(ContentPostPO po) {
        if (po == null) {
            return false;
        }
        if (po.getStatus() == null || po.getStatus() != ContentPostStatusEnumVO.PUBLISHED.getCode()) {
            return false;
        }
        return po.getVisibility() != null && po.getVisibility() == ContentPostVisibilityEnumVO.PUBLIC.getCode();
    }

    private SearchDocumentVO buildDocument(ContentPostPO po,
                                           Map<Long, List<String>> typesByPostId,
                                           Map<Long, UserBasePO> usersById,
                                           Map<String, String> contentByUuid) {
        UserBasePO user = usersById.get(po.getUserId());
        String contentText = "";
        String uuid = po.getContentUuid();
        if (uuid != null && !uuid.isBlank()) {
            contentText = contentByUuid.getOrDefault(uuid.trim(), "");
        }
        long likeCount = reactionRepository.getCount(ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(po.getPostId())
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build());
        return searchDocumentAssembler.assemble(
                po.getPostId(),
                po.getUserId(),
                po.getTitle(),
                po.getSummary(),
                contentText,
                typesByPostId.getOrDefault(po.getPostId(), List.of()),
                user == null ? null : user.getAvatarUrl(),
                user == null ? null : user.getNickname(),
                po.getPublishTime() == null ? null : po.getPublishTime().getTime(),
                Math.max(0L, likeCount),
                po.getMediaInfo());
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
            if (row == null || row.getPostId() == null || row.getType() == null || row.getType().isBlank()) {
                continue;
            }
            map.computeIfAbsent(row.getPostId(), k -> new ArrayList<>()).add(row.getType().trim());
        }
        return map;
    }

    private Map<Long, UserBasePO> loadUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<Long> dedup = new LinkedHashSet<>(userIds);
        List<UserBasePO> rows = userBaseDao.selectByUserIds(new ArrayList<>(dedup));
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserBasePO> map = new HashMap<>();
        for (UserBasePO row : rows) {
            if (row != null && row.getUserId() != null) {
                map.put(row.getUserId(), row);
            }
        }
        return map;
    }

    private Map<String, String> loadContents(List<ContentPostPO> page) {
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (ContentPostPO po : page) {
            if (po == null || po.getContentUuid() == null || po.getContentUuid().isBlank()) {
                continue;
            }
            dedup.add(po.getContentUuid().trim());
        }
        if (dedup.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, String> map = postContentKvPort.findBatch(new ArrayList<>(dedup));
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            log.warn("load post contents from kv failed, size={}", dedup.size(), e);
            return Map.of();
        }
    }

    private Cursor loadCheckpoint() {
        try {
            String raw = stringRedisTemplate.opsForValue().get(checkpointKey);
            if (raw == null || raw.isBlank()) {
                return Cursor.empty();
            }
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) {
                return Cursor.empty();
            }
            return new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (Exception e) {
            log.warn("search backfill checkpoint load failed, key={}", checkpointKey, e);
            return Cursor.empty();
        }
    }

    private void saveCheckpoint(String key, long lastPublishTimeMs, long lastPostId) {
        try {
            stringRedisTemplate.opsForValue().set(key, lastPublishTimeMs + ":" + lastPostId);
        } catch (Exception e) {
            log.warn("search backfill checkpoint save failed, key={}, cursor={}:{}", key, lastPublishTimeMs, lastPostId, e);
        }
    }

    private static final class Cursor {
        private final Long cursorPublishTimeMs;
        private final Long cursorPostId;

        private Cursor(Long cursorPublishTimeMs, Long cursorPostId) {
            this.cursorPublishTimeMs = cursorPublishTimeMs;
            this.cursorPostId = cursorPostId;
        }

        private static Cursor empty() {
            return new Cursor(null, null);
        }
    }
}
