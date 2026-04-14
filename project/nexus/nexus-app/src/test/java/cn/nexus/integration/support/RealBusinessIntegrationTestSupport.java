package cn.nexus.integration.support;

import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.counter.adapter.port.IObjectCounterPort;
import cn.nexus.domain.social.adapter.port.ICommentContentKvPort;
import cn.nexus.domain.social.adapter.port.IContentEventOutboxPort;
import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.user.adapter.port.IUserEventOutboxPort;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisSchema;
import cn.nexus.infrastructure.adapter.social.repository.CommentHotRankRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedGlobalLatestRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedOutboxRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedTimelineRepository;
import cn.nexus.infrastructure.adapter.social.repository.RelationRepository;
import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.dao.auth.IAuthAccountDao;
import cn.nexus.infrastructure.dao.auth.IAuthRoleDao;
import cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao;
import cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao;
import cn.nexus.infrastructure.dao.auth.po.AuthRolePO;
import cn.nexus.infrastructure.dao.auth.po.AuthUserRolePO;
import cn.nexus.infrastructure.dao.social.IContentPostDao;
import cn.nexus.infrastructure.dao.social.IContentPostTypeDao;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.IUserPrivacyDao;
import cn.nexus.infrastructure.dao.user.IUserEventOutboxDao;
import cn.nexus.infrastructure.dao.user.IUserStatusDao;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.trigger.mq.config.CountPostLike2SearchIndexMqConfig;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackMqConfig;
import cn.nexus.trigger.mq.config.FeedRecommendItemMqConfig;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackAMqConfig;
import cn.nexus.trigger.mq.config.InteractionNotifyMqConfig;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.trigger.mq.config.LikeUnlikeMqConfig;
import cn.nexus.trigger.mq.config.ReactionEventLogMqConfig;
import cn.nexus.trigger.mq.config.RelationMqConfig;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import cn.nexus.trigger.mq.config.SearchIndexMqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

public abstract class RealBusinessIntegrationTestSupport {

    private static final AtomicLong UNIQUE_ID_SEQ = new AtomicLong(System.currentTimeMillis() * 1000L);

    @Autowired
    protected ISocialIdPort socialIdPort;

    @Autowired
    protected IUserBaseDao userBaseDao;

    @Autowired
    protected IUserPrivacyDao userPrivacyDao;

    @Autowired
    protected IUserStatusDao userStatusDao;

    @Autowired
    protected IUserEventOutboxDao userEventOutboxDao;

    @Autowired
    protected IAuthAccountDao authAccountDao;

    @Autowired
    protected IAuthSmsCodeDao authSmsCodeDao;

    @Autowired
    protected IAuthRoleDao authRoleDao;

    @Autowired
    protected IAuthUserRoleDao authUserRoleDao;

    @Autowired
    protected IContentPostDao contentPostDao;

    @Autowired
    protected IContentPostTypeDao contentPostTypeDao;

    @Autowired
    protected IObjectCounterPort objectCounterPort;

    @Autowired
    protected IPostContentKvPort postContentKvPort;

    @Autowired
    protected ICommentContentKvPort commentContentKvPort;

    @Autowired
    protected IMediaStoragePort mediaStoragePort;

    @Autowired
    protected RelationRepository relationRepository;

    @Autowired
    protected FeedTimelineRepository feedTimelineRepository;

    @Autowired
    protected FeedOutboxRepository feedOutboxRepository;

    @Autowired
    protected FeedGlobalLatestRepository feedGlobalLatestRepository;

    @Autowired
    protected CommentHotRankRepository commentHotRankRepository;

    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected RestClient searchRestClient;

    @Autowired
    protected ReliableMqOutboxService reliableMqOutboxService;

    @Autowired
    protected IContentEventOutboxPort contentEventOutboxPort;

    @Autowired
    protected IUserEventOutboxPort userEventOutboxPort;

    @Autowired
    protected DataSource dataSource;

    @Value("${search.es.indexAlias}")
    protected String indexAlias;

    @Value("${feed.recommend.baseUrl:}")
    protected String gorseBaseUrl;

    protected final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    protected final HttpClient middlewareHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void setUpRealBusinessSupport() {
        clearMiddlewareOutboxTables();
        purgeQueueQuietly(FeedFanoutConfig.QUEUE);
        purgeQueueQuietly(FeedFanoutConfig.TASK_QUEUE);
        purgeQueueQuietly(FeedFanoutConfig.DLQ_POST_PUBLISHED);
        purgeQueueQuietly(FeedFanoutConfig.DLQ_FANOUT_TASK);
        purgeQueueQuietly(FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_UPSERT);
        purgeQueueQuietly(FeedRecommendItemMqConfig.DLQ_FEED_RECOMMEND_ITEM_UPSERT);
        purgeQueueQuietly(FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_DELETE);
        purgeQueueQuietly(FeedRecommendItemMqConfig.DLQ_FEED_RECOMMEND_ITEM_DELETE);
        purgeQueueQuietly(FeedRecommendFeedbackMqConfig.QUEUE);
        purgeQueueQuietly(FeedRecommendFeedbackMqConfig.DLQ_RECOMMEND_FEEDBACK);
        purgeQueueQuietly(InteractionCommentMqConfig.Q_COMMENT_CREATED);
        purgeQueueQuietly(InteractionCommentMqConfig.Q_COMMENT_LIKE_CHANGED);
        purgeQueueQuietly(InteractionCommentMqConfig.Q_REPLY_COUNT_CHANGED);
        purgeQueueQuietly(InteractionNotifyMqConfig.Q_INTERACTION_NOTIFY);
        purgeQueueQuietly(InteractionNotifyMqConfig.DLQ_INTERACTION_NOTIFY);
        purgeQueueQuietly(ReactionEventLogMqConfig.QUEUE);
        purgeQueueQuietly(ReactionEventLogMqConfig.DLQ);
        purgeQueueQuietly(FeedRecommendFeedbackAMqConfig.Q_FEED_RECOMMEND_FEEDBACK_A);
        purgeQueueQuietly(FeedRecommendFeedbackAMqConfig.DLQ_FEED_RECOMMEND_FEEDBACK_A);
        purgeQueueQuietly(LikeUnlikeMqConfig.QUEUE_COUNT);
        purgeQueueQuietly(LikeUnlikeMqConfig.DLQ_COUNT);
        purgeQueueQuietly(CountPostLike2SearchIndexMqConfig.QUEUE);
        purgeQueueQuietly(CountPostLike2SearchIndexMqConfig.DLQ);
        purgeQueueQuietly(RelationMqConfig.Q_FOLLOW);
        purgeQueueQuietly(RelationMqConfig.Q_BLOCK);
        purgeQueueQuietly(RiskMqConfig.Q_LLM_SCAN);
        purgeQueueQuietly(RiskMqConfig.DLQ_LLM_SCAN);
        purgeQueueQuietly(RiskMqConfig.Q_IMAGE_SCAN);
        purgeQueueQuietly(RiskMqConfig.DLQ_IMAGE_SCAN);
        purgeQueueQuietly(RiskMqConfig.Q_REVIEW_CASE);
        purgeQueueQuietly(RiskMqConfig.DLQ_REVIEW_CASE);
        purgeQueueQuietly(SearchIndexMqConfig.Q_USER_NICKNAME_CHANGED);
        purgeQueueQuietly(SearchIndexMqConfig.DLQ_USER_NICKNAME_CHANGED);
        ensureSearchIndexReady();
    }

    protected void clearMiddlewareOutboxTables() {
        // 测试环境允许清空：避免历史遗留 outbox 积压导致“新事件被 scan limit 饥饿”。
        execUpdate("DELETE FROM content_event_outbox");
        execUpdate("DELETE FROM relation_event_outbox");
        execUpdate("DELETE FROM relation_event_inbox");
        execUpdate("DELETE FROM interaction_reaction_event_log");
        execUpdate("DELETE FROM reliable_mq_outbox");
        execUpdate("DELETE FROM reliable_mq_consumer_record");
        execUpdate("DELETE FROM reliable_mq_replay_record");
    }

    protected int execUpdate(String sql) {
        if (sql == null || sql.isBlank()) {
            return 0;
        }
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        } catch (Exception e) {
            throw new IllegalStateException("execute sql failed: " + sql, e);
        }
    }

    protected long uniqueId() {
        return socialIdPort.nextId();
    }

    protected String uniqueUuid() {
        return UUID.randomUUID().toString();
    }

    protected String uniquePhone() {
        long suffix = Math.abs(uniqueId()) % 100_000_000L;
        return "139" + String.format("%08d", suffix);
    }

    protected Date nowDate() {
        return new Date();
    }

    protected void deleteRedisKey(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(key);
    }

    protected void deleteRedisHashField(String key, String field) {
        if (key == null || key.isBlank() || field == null || field.isBlank()) {
            return;
        }
        stringRedisTemplate.opsForHash().delete(key, field);
    }

    protected long readRedisLong(String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        try {
            String raw = stringRedisTemplate.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                return 0L;
            }
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (Exception e) {
            throw new IllegalStateException("read redis value failed, key=" + key, e);
        }
    }

    protected long readObjectSnapshotCount(ReactionTargetTypeEnumVO targetType, Long targetId, ObjectCounterType counterType) {
        if (targetType == null || targetId == null || counterType == null) {
            return 0L;
        }
        ObjectCounterTarget target = ObjectCounterTarget.builder()
                .targetType(targetType)
                .targetId(targetId)
                .counterType(counterType)
                .build();
        String key = CountRedisKeys.objectSnapshot(target);
        CountRedisSchema schema = CountRedisSchema.forObject(targetType);
        int slot = schema == null ? -1 : schema.slotOf(counterType);
        return readSnapshotSlot(key, schema, slot);
    }

    protected long readUserSnapshotCount(Long userId, UserCounterType counterType) {
        if (userId == null || counterType == null) {
            return 0L;
        }
        String key = CountRedisKeys.userSnapshot(userId);
        CountRedisSchema schema = CountRedisSchema.user();
        int slot = schema.slotOf(counterType);
        return readSnapshotSlot(key, schema, slot);
    }

    private long readSnapshotSlot(String key, CountRedisSchema schema, int slot) {
        if (key == null || key.isBlank() || schema == null || slot < 0) {
            return 0L;
        }
        try {
            String raw = stringRedisTemplate.opsForValue().get(key);
            byte[] bytes = CountRedisCodec.fromRedisValue(raw);
            long[] values = CountRedisCodec.decodeSlots(bytes, schema.slotCount());
            if (slot >= values.length) {
                return 0L;
            }
            return Math.max(0L, values[slot]);
        } catch (Exception e) {
            throw new IllegalStateException("read count redis snapshot failed, key=" + key + ", slot=" + slot, e);
        }
    }

    protected long queryReactionEventLogCount(String targetType, long targetId, String reactionType) {
        String sql = "SELECT COUNT(1) FROM interaction_reaction_event_log WHERE target_type = ? AND target_id = ? AND reaction_type = ?";
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetType);
            ps.setLong(2, targetId);
            ps.setString(3, reactionType);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query interaction_reaction_event_log count failed, targetType=" + targetType
                    + ", targetId=" + targetId + ", reactionType=" + reactionType, e);
        }
    }

    protected void clearFeedKeys(long authorId, long... inboxUserIds) {
        deleteRedisKey("feed:outbox:" + authorId);
        for (long userId : inboxUserIds) {
            deleteRedisKey("feed:inbox:" + userId);
        }
    }

    protected void clearAuthThrottleKeys(String phone) {
        deleteRedisKey("auth:sms:send:phone:" + phone + ":1m");
        deleteRedisKey("auth:sms:send:phone:" + phone + ":1h");
        deleteRedisKey("auth:sms:send:phone:" + phone + ":1d");
        deleteRedisKey("auth:sms:send:ip:127.0.0.1:1m");
        deleteRedisKey("auth:sms:send:ip:127.0.0.1:1d");
        deleteRedisKey("auth:login:fail:password:" + phone);
        deleteRedisKey("auth:login:lock:password:" + phone);
        deleteRedisKey("auth:login:fail:sms:" + phone);
        deleteRedisKey("auth:login:lock:sms:" + phone);
    }

    protected void grantRole(long userId, String roleCode) {
        AuthRolePO role = authRoleDao.selectByRoleCode(roleCode);
        if (role == null || role.getRoleId() == null) {
            throw new IllegalStateException("role not found: " + roleCode);
        }
        AuthUserRolePO po = new AuthUserRolePO();
        po.setId(uniqueId());
        po.setUserId(userId);
        po.setRoleId(role.getRoleId());
        authUserRoleDao.insertIgnore(po);
    }

    protected List<FeedInboxEntryVO> inboxEntries(long userId, int limit) {
        return feedTimelineRepository.pageInboxEntries(userId, null, null, limit);
    }

    protected JsonNode fetchDocumentSource(long contentId) throws Exception {
        try {
            Response response = searchRestClient.performRequest(new Request("GET", "/" + indexAlias + "/_doc/" + contentId));
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(body);
            if (!root.path("found").asBoolean(false)) {
                return null;
            }
            return root.path("_source");
        } catch (ResponseException e) {
            int statusCode = e.getResponse() == null ? 500 : e.getResponse().getStatusLine().getStatusCode();
            if (statusCode == 404 || statusCode == 503) {
                return null;
            }
            throw e;
        }
    }

    protected void deleteDocumentQuietly(long contentId) {
        try {
            searchRestClient.performRequest(new Request("DELETE", "/" + indexAlias + "/_doc/" + contentId));
        } catch (ResponseException e) {
            int statusCode = e.getResponse() == null ? 500 : e.getResponse().getStatusLine().getStatusCode();
            if (statusCode != 404) {
                throw new IllegalStateException("delete elasticsearch doc failed, contentId=" + contentId, e);
            }
        } catch (Exception e) {
            throw new IllegalStateException("delete elasticsearch doc failed, contentId=" + contentId, e);
        }
    }

    protected List<String> tagsOf(JsonNode source) {
        if (source == null || !source.path("tags").isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(source.path("tags"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    protected void purgeQueueQuietly(String queueName) {
        try {
            rabbitTemplate.execute(channel -> {
                channel.queuePurge(queueName);
                return null;
            });
        } catch (Exception ignored) {
            // 忽略清理失败，交给后续断言暴露真实问题。
        }
    }

    protected void publishPendingReliableMqMessages() {
        // 真实链路测试里，同一个场景可能会触发多条 outbox 记录（通知/评论/点赞等）。
        // 同时，ReliableMqPolicy 的重试退避首跳就是 60s：一旦 publishReady 遇到瞬时失败（如 MQ 拓扑尚未声明完成），
        // 不主动把 next_retry_at 拉回 NOW()，测试会被迫等 60s+。
        for (int i = 0; i < 10; i++) {
            execUpdate("UPDATE reliable_mq_outbox SET next_retry_at = NOW() WHERE status IN ('PENDING','RETRY_PENDING')");
            reliableMqOutboxService.publishReady(200);
        }
    }

    protected void publishPendingContentEvents() {
        // 避免 outbox FAIL 记录因退避 next_retry_time 导致测试要等 60s+ 才能重试。
        execUpdate("UPDATE content_event_outbox SET next_retry_time = NOW() WHERE status IN ('NEW','FAIL')");
        contentEventOutboxPort.tryPublishPending();
    }

    protected void publishPendingUserEvents() {
        execUpdate("UPDATE user_event_outbox SET status = 'NEW' WHERE status = 'FAIL'");
        userEventOutboxPort.tryPublishPending();
    }

    protected void ensureSearchIndexReady() {
        try {
            if (isSearchIndexMappingCompatible()) {
                return;
            }
            recreateSearchIndex();
        } catch (Exception e) {
            throw new IllegalStateException("ensure search index ready failed: " + indexAlias, e);
        }
    }

    private boolean isSearchIndexMappingCompatible() throws Exception {
        try {
            Response response = searchRestClient.performRequest(new Request("GET", "/" + indexAlias + "/_mapping"));
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(body);
            JsonNode properties = locateIndexProperties(root);
            return hasFieldType(properties, "title_suggest", "completion")
                    && hasFieldType(properties, "status", "keyword")
                    && hasFieldType(properties, "tags", "keyword")
                    && hasFieldType(properties, "publish_time", "date");
        } catch (ResponseException e) {
            int statusCode = e.getResponse() == null ? 500 : e.getResponse().getStatusLine().getStatusCode();
            if (statusCode == 404) {
                return false;
            }
            throw e;
        }
    }

    private JsonNode locateIndexProperties(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return objectMapper.createObjectNode();
        }
        JsonNode direct = root.path(indexAlias).path("mappings").path("properties");
        if (!direct.isMissingNode() && !direct.isNull()) {
            return direct;
        }
        Iterator<String> fields = root.fieldNames();
        if (!fields.hasNext()) {
            return objectMapper.createObjectNode();
        }
        return root.path(fields.next()).path("mappings").path("properties");
    }

    private boolean hasFieldType(JsonNode properties, String field, String expectedType) {
        return expectedType.equals(properties.path(field).path("type").asText());
    }

    private void recreateSearchIndex() throws Exception {
        try {
            searchRestClient.performRequest(new Request("DELETE", "/" + indexAlias));
        } catch (ResponseException e) {
            int statusCode = e.getResponse() == null ? 500 : e.getResponse().getStatusLine().getStatusCode();
            if (statusCode != 404) {
                throw e;
            }
        }
        Request request = new Request("PUT", "/" + indexAlias);
        request.setJsonEntity(buildSearchIndexBody().toString());
        searchRestClient.performRequest(request);
    }

    private ObjectNode buildSearchIndexBody() {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode properties = root.putObject("mappings").putObject("properties");
        properties.putObject("content_id").put("type", "long");
        properties.putObject("content_type").put("type", "keyword");
        properties.putObject("description").put("type", "text");
        properties.putObject("title").put("type", "text");
        properties.putObject("body").put("type", "text");
        properties.putObject("tags").put("type", "keyword");
        properties.putObject("author_id").put("type", "long");
        properties.putObject("author_avatar").put("type", "keyword");
        properties.putObject("author_nickname").put("type", "keyword");
        properties.putObject("author_tag_json").put("type", "keyword");
        properties.putObject("publish_time").put("type", "date").put("format", "epoch_millis");
        properties.putObject("like_count").put("type", "integer");
        properties.putObject("favorite_count").put("type", "integer");
        properties.putObject("view_count").put("type", "integer");
        properties.putObject("status").put("type", "keyword");
        properties.putObject("img_urls").put("type", "keyword");
        properties.putObject("is_top").put("type", "keyword");
        properties.putObject("title_suggest").put("type", "completion");
        return root;
    }

    protected JsonNode gorseGetJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeGorseUrl(path)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = middlewareHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("gorse GET failed, status=" + response.statusCode() + ", path=" + path + ", body=" + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    protected JsonNode gorseGetJsonOrNull(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeGorseUrl(path)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = middlewareHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("gorse GET failed, status=" + response.statusCode() + ", path=" + path + ", body=" + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    protected List<Long> gorseLatestItems(int n) throws Exception {
        JsonNode root = gorseGetJson("/api/non-personalized/latest?n=" + Math.max(1, n));
        return objectMapper.convertValue(
                root.findValuesAsText("Id"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class)
        );
    }

    protected JsonNode gorseItemOrNull(long postId) throws Exception {
        return gorseGetJsonOrNull("/api/item/" + postId);
    }

    protected JsonNode gorseFeedbackPage(int n) throws Exception {
        return gorseGetJson("/api/feedback?n=" + Math.max(1, n));
    }

    protected String reliableConsumerStatus(String eventId, String consumerName) {
        if (eventId == null || eventId.isBlank() || consumerName == null || consumerName.isBlank()) {
            return null;
        }
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM reliable_mq_consumer_record WHERE event_id = ? AND consumer_name = ? LIMIT 1")) {
            ps.setString(1, eventId.trim());
            ps.setString(2, consumerName.trim());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query reliable_mq_consumer_record status failed, eventId=" + eventId + ", consumerName=" + consumerName, e);
        }
    }

    protected String reliableConsumerPayload(String eventId, String consumerName) {
        if (eventId == null || eventId.isBlank() || consumerName == null || consumerName.isBlank()) {
            return null;
        }
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT payload_json FROM reliable_mq_consumer_record WHERE event_id = ? AND consumer_name = ? LIMIT 1")) {
            ps.setString(1, eventId.trim());
            ps.setString(2, consumerName.trim());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query reliable_mq_consumer_record payload failed, eventId=" + eventId + ", consumerName=" + consumerName, e);
        }
    }

    protected String reliableConsumerStatusByPayload(String consumerName, String payloadNeedle) {
        if (consumerName == null || consumerName.isBlank() || payloadNeedle == null || payloadNeedle.isBlank()) {
            return null;
        }
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM reliable_mq_consumer_record WHERE consumer_name = ? AND payload_json LIKE ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, consumerName.trim());
            ps.setString(2, "%" + payloadNeedle.trim() + "%");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query reliable_mq_consumer_record status by payload failed, consumerName=" + consumerName + ", payloadNeedle=" + payloadNeedle, e);
        }
    }

    protected String reliableConsumerPayloadByPayload(String consumerName, String payloadNeedle) {
        if (consumerName == null || consumerName.isBlank() || payloadNeedle == null || payloadNeedle.isBlank()) {
            return null;
        }
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT payload_json FROM reliable_mq_consumer_record WHERE consumer_name = ? AND payload_json LIKE ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, consumerName.trim());
            ps.setString(2, "%" + payloadNeedle.trim() + "%");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query reliable_mq_consumer_record payload by payload failed, consumerName=" + consumerName + ", payloadNeedle=" + payloadNeedle, e);
        }
    }

    private String normalizeGorseUrl(String path) {
        String raw = (gorseBaseUrl == null || gorseBaseUrl.isBlank()) ? "http://127.0.0.1:8087" : gorseBaseUrl;
        String base = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        if (path == null || path.isBlank()) {
            return base;
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }
}
