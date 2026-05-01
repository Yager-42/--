package cn.nexus.integration.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import cn.nexus.trigger.counter.CounterAggregationConsumer;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

class HighConcurrencyConsistencyAuditIntegrationTest extends RealHttpIntegrationTestSupport {

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @Autowired
    private CounterAggregationConsumer counterAggregationConsumer;

    @Test
    void postLike_highConcurrencyAudit_shouldAlignRedisSnapshotAndApiState() throws Exception {
        ensureAllConsumersStarted();
        TestSession author = registerAndLoginSession("audit-like-author");
        List<TestSession> likers = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            likers.add(registerAndLoginSession("audit-like-user-" + i));
        }

        long postId = seedPublishedPost(author.userId(), "audit-like-post-" + uniqueUuid().substring(0, 6), "audit-like-body-" + uniqueUuid());
        deleteRedisKey("interact:content:author:" + postId);

        AtomicInteger rr = new AtomicInteger(0);
        ConcurrentRunResult result = runConcurrentRequests(likers.size(), 20, 60, () -> {
            TestSession liker = likers.get(Math.floorMod(rr.getAndIncrement(), likers.size()));
            JsonNode data = assertSuccess(postJson("/api/v1/action/like", JsonNodeFactory.instance.objectNode()
                    .put("requestId", "audit-like-" + uniqueUuid())
                    .put("targetId", postId)
                    .put("targetType", "post"), liker.token()));
            assertThat(data.path("changed").asBoolean()).isTrue();
        });
        assertThat(result.failure()).isEqualTo(0);

        int expected = likers.size();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            counterAggregationConsumer.flushActiveBuckets();
            JsonNode counter = assertSuccess(getJson("/api/v1/counter/post/" + postId + "?metrics=like", likers.get(0).token()));
            assertThat(counter.path("counts").path("like").asLong()).isEqualTo(expected);
        });

        JsonNode detail = assertSuccess(getJson("/api/v1/content/" + postId, likers.get(0).token()));
        assertThat(detail.path("liked").asBoolean()).isTrue();
        assertThat(detail.path("likeCount").asLong()).isEqualTo(expected);
    }

    @Test
    void relationFollow_highConcurrencyAudit_shouldKeepSingleActiveEdge() throws Exception {
        TestSession followee = registerAndLoginSession("audit-followee");
        TestSession follower = registerAndLoginSession("audit-follower");

        ConcurrentRunResult result = runConcurrentRequests(120, 24, 60, () -> {
            JsonNode data = assertSuccess(postJson("/api/v1/relation/follow", JsonNodeFactory.instance.objectNode()
                    .put("targetId", followee.userId()), follower.token()));
            assertThat(data.path("status").asText()).isNotBlank();
        });
        assertThat(result.failure()).isEqualTo(0);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queryActiveFollowEdgeCount(follower.userId(), followee.userId())).isEqualTo(1L));
        ObjectNode batchReq = JsonNodeFactory.instance.objectNode();
        batchReq.putArray("targetUserIds").add(followee.userId());
        JsonNode stateBatch = assertSuccess(postJson("/api/v1/relation/state/batch", batchReq, follower.token()));
        assertThat(stateBatch.path("followingUserIds")).extracting(JsonNode::asLong).contains(followee.userId());
    }

    @Test
    void contentPublishDelete_audit_shouldAlignMysqlStatusAndSearchIndex() throws Exception {
        TestSession author = registerAndLoginSession("audit-content-author");

        long postId = assertSuccess(putJson("/api/v1/content/draft", JsonNodeFactory.instance.objectNode()
                .put("title", "audit-draft-" + uniqueUuid().substring(0, 6))
                .put("contentText", "audit-content-" + uniqueUuid()), author.token()))
                .path("draftId").asLong();
        assertThat(postId).isPositive();

        String title = "audit-publish-title-" + uniqueUuid().substring(0, 6);
        String body = "audit-publish-body-" + uniqueUuid();
        JsonNode published = assertSuccess(postJson("/api/v1/content/publish", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("title", title)
                .put("text", body)
                .put("visibility", "PUBLIC"), author.token()));
        assertThat(published.path("postId").asLong()).isEqualTo(postId);

        publishPendingContentEvents();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("status").asText()).isEqualTo("published");
        });
        assertThat(queryContentPostStatus(postId)).isEqualTo(ContentPostStatusEnumVO.PUBLISHED.getCode());

        JsonNode deleted = assertSuccess(deleteJson("/api/v1/content/" + postId, JsonNodeFactory.instance.objectNode(), author.token()));
        assertThat(deleted.path("success").asBoolean()).isTrue();

        publishPendingContentEvents();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("status").asText()).isEqualTo("deleted");
        });
        assertThat(queryContentPostStatus(postId)).isEqualTo(ContentPostStatusEnumVO.DELETED.getCode());
    }

    private long seedPublishedPost(long authorId, String title, String body) {
        long postId = uniqueId();
        Date now = new Date();
        ContentPostPO post = new ContentPostPO();
        post.setPostId(postId);
        post.setUserId(authorId);
        post.setTitle(title);
        post.setContentUuid(uniqueUuid());
        post.setSummary("consistency-audit");
        post.setSummaryStatus(1);
        post.setMediaType(0);
        post.setStatus(ContentPostStatusEnumVO.PUBLISHED.getCode());
        post.setVisibility(ContentPostVisibilityEnumVO.PUBLIC.getCode());
        post.setVersionNum(1);
        post.setIsEdited(0);
        post.setCreateTime(now);
        post.setPublishTime(now);
        contentPostDao.insert(post);
        postContentKvPort.add(post.getContentUuid(), body);
        return postId;
    }

    private long queryActiveFollowEdgeCount(long sourceId, long targetId) {
        String sql = "SELECT COUNT(1) FROM user_relation WHERE source_id = ? AND target_id = ? AND relation_type = 1 AND status = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sourceId);
            ps.setLong(2, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query user_relation failed, sourceId=" + sourceId + ", targetId=" + targetId, e);
        }
    }

    private int queryContentPostStatus(long postId) {
        String sql = "SELECT status FROM content_post WHERE post_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query content_post status failed, postId=" + postId, e);
        }
    }

    private void ensureAllConsumersStarted() {
        rabbitListenerEndpointRegistry.getListenerContainers().forEach(container -> {
            try {
                if (!container.isRunning()) {
                    container.start();
                }
            } catch (Exception ignored) {
                // Let downstream assertions expose readiness issues.
            }
        });
    }

    private TestSession registerAndLoginSession(String nicknamePrefix) throws Exception {
        String phone = uniquePhone();
        String password = "Pwd@" + uniqueUuid().substring(0, 8);
        String nickname = nicknamePrefix + "-" + uniqueUuid().substring(0, 6);
        String token = registerAndLogin(phone, password, nickname);
        long userId = assertSuccess(getJson("/api/v1/auth/me", token)).path("userId").asLong();
        return new TestSession(userId, token);
    }

    private record TestSession(long userId, String token) {
    }
}
