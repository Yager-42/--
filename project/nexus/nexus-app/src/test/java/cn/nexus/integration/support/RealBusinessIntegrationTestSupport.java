package cn.nexus.integration.support;

import static org.mockito.BDDMockito.given;

import cn.nexus.domain.social.adapter.port.ICommentContentKvPort;
import cn.nexus.domain.social.adapter.port.IContentEventOutboxPort;
import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.adapter.id.LeafSnowflakeIdGenerator;
import cn.nexus.infrastructure.adapter.social.repository.CommentHotRankRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedGlobalLatestRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedOutboxRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedTimelineRepository;
import cn.nexus.infrastructure.adapter.social.repository.ReactionRepository;
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
import cn.nexus.trigger.mq.config.CountPostLikeMqConfig;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackMqConfig;
import cn.nexus.trigger.mq.config.FeedRecommendItemMqConfig;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackAMqConfig;
import cn.nexus.trigger.mq.config.InteractionNotifyMqConfig;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.trigger.mq.config.LikeUnlikeMqConfig;
import cn.nexus.trigger.mq.config.RelationMqConfig;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import cn.nexus.trigger.mq.config.SearchIndexMqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

public abstract class RealBusinessIntegrationTestSupport {

    @MockBean
    protected ISocialIdPort socialIdPort;

    @MockBean
    protected LeafSnowflakeIdGenerator leafSnowflakeIdGenerator;

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
    protected ReactionRepository reactionRepository;

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
    protected DataSource dataSource;

    @Value("${search.es.indexAlias}")
    protected String indexAlias;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpRealBusinessSupport() {
        clearMiddlewareOutboxTables();
        given(leafSnowflakeIdGenerator.nextId()).willAnswer(invocation -> uniqueId());
        given(socialIdPort.nextId()).willAnswer(invocation -> uniqueId());
        given(socialIdPort.now()).willAnswer(invocation -> System.currentTimeMillis());
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
        purgeQueueQuietly(FeedRecommendFeedbackAMqConfig.Q_FEED_RECOMMEND_FEEDBACK_A);
        purgeQueueQuietly(FeedRecommendFeedbackAMqConfig.DLQ_FEED_RECOMMEND_FEEDBACK_A);
        purgeQueueQuietly(LikeUnlikeMqConfig.QUEUE_PERSIST);
        purgeQueueQuietly(LikeUnlikeMqConfig.DLQ_PERSIST);
        purgeQueueQuietly(LikeUnlikeMqConfig.QUEUE_COUNT);
        purgeQueueQuietly(LikeUnlikeMqConfig.DLQ_COUNT);
        purgeQueueQuietly(CountPostLikeMqConfig.QUEUE);
        purgeQueueQuietly(CountPostLikeMqConfig.DLQ);
        purgeQueueQuietly(RelationMqConfig.Q_FOLLOW);
        purgeQueueQuietly(RelationMqConfig.Q_BLOCK);
        purgeQueueQuietly(RiskMqConfig.Q_LLM_SCAN);
        purgeQueueQuietly(RiskMqConfig.DLQ_LLM_SCAN);
        purgeQueueQuietly(RiskMqConfig.Q_IMAGE_SCAN);
        purgeQueueQuietly(RiskMqConfig.DLQ_IMAGE_SCAN);
        purgeQueueQuietly(RiskMqConfig.Q_REVIEW_CASE);
        purgeQueueQuietly(RiskMqConfig.DLQ_REVIEW_CASE);
        purgeQueueQuietly(SearchIndexMqConfig.Q_POST_PUBLISHED);
        purgeQueueQuietly(SearchIndexMqConfig.DLQ_POST_PUBLISHED);
        purgeQueueQuietly(SearchIndexMqConfig.Q_POST_UPDATED);
        purgeQueueQuietly(SearchIndexMqConfig.DLQ_POST_UPDATED);
        purgeQueueQuietly(SearchIndexMqConfig.Q_POST_DELETED);
        purgeQueueQuietly(SearchIndexMqConfig.DLQ_POST_DELETED);
        purgeQueueQuietly(SearchIndexMqConfig.Q_USER_NICKNAME_CHANGED);
        purgeQueueQuietly(SearchIndexMqConfig.DLQ_USER_NICKNAME_CHANGED);
        ensureSearchIndexReady();
    }

    protected void clearMiddlewareOutboxTables() {
        // 测试环境允许清空：避免历史遗留 outbox 积压导致“新事件被 scan limit 饥饿”。
        execUpdate("DELETE FROM content_event_outbox");
        execUpdate("DELETE FROM relation_event_outbox");
        execUpdate("DELETE FROM relation_event_inbox");
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
        return System.currentTimeMillis() * 1000L + ThreadLocalRandom.current().nextInt(1000);
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
}
