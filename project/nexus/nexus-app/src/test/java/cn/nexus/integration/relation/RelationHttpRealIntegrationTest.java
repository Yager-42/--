package cn.nexus.integration.relation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import cn.nexus.trigger.job.social.RelationEventOutboxPublishJob;
import cn.nexus.trigger.mq.config.RelationMqConfig;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class RelationHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Autowired
    private RelationEventOutboxPublishJob relationEventOutboxPublishJob;

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @Test
    void followUnfollowAndQueries_shouldRunThroughRealChainAndBackfillFeedInbox() throws Exception {
        TestSession followee = registerAndLoginSession("followee");
        TestSession follower = registerAndLoginSession("follower");

        ensureRelationTopology();
        ensureRelationConsumersReady();

        long postId = seedPublishedPost(followee.userId());

        // follower 在线态：inbox key 存在才会触发 follow 补偿回填。
        clearFeedKeys(follower.userId(), follower.userId());
        feedTimelineRepository.replaceInbox(follower.userId(), List.of());

        JsonNode follow = postJson("/api/v1/relation/follow", JsonNodeFactory.instance.objectNode()
                .put("targetId", followee.userId()), follower.token());
        assertThat(assertSuccess(follow).path("status").asText()).isEqualTo("ACTIVE");

        var stateBatchReq = JsonNodeFactory.instance.objectNode();
        stateBatchReq.putArray("targetUserIds").add(followee.userId());
        JsonNode stateBatch = assertSuccess(postJson("/api/v1/relation/state/batch", stateBatchReq, follower.token()));
        assertThat(stateBatch.path("followingUserIds")).extracting(JsonNode::asLong).contains(followee.userId());

        Long followEventId = latestRelationOutboxEventId("FOLLOW");
        assertThat(followEventId).isNotNull();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingRelationEvents();
            assertThat(relationOutboxStatus(followEventId)).isEqualTo("DONE");
            assertThat(relationInboxStatus(String.valueOf(followEventId))).isEqualTo("PROCESSED");
            assertThat(inboxEntries(follower.userId(), 20))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
        });

        JsonNode following = assertSuccess(getJson("/api/v1/relation/following?userId=" + follower.userId() + "&limit=10", follower.token()));
        assertThat(following.path("items"))
                .extracting(JsonNode::toString)
                .anySatisfy(raw -> assertThat(raw).contains("\"userId\":" + followee.userId()));

        JsonNode unfollow = postJson("/api/v1/relation/unfollow", JsonNodeFactory.instance.objectNode()
                .put("targetId", followee.userId()), follower.token());
        assertThat(assertSuccess(unfollow).path("status").asText()).isIn("UNFOLLOWED", "NOT_FOLLOWING");

        var stateBatchReq2 = JsonNodeFactory.instance.objectNode();
        stateBatchReq2.putArray("targetUserIds").add(followee.userId());
        JsonNode stateBatch2 = assertSuccess(postJson("/api/v1/relation/state/batch", stateBatchReq2, follower.token()));
        assertThat(stateBatch2.path("followingUserIds")).isEmpty();

        Long unfollowEventId = latestRelationOutboxEventId("FOLLOW");
        assertThat(unfollowEventId).isNotNull();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                {
                    publishPendingRelationEvents();
                    assertThat(relationOutboxStatus(unfollowEventId)).isEqualTo("DONE");
                    assertThat(relationInboxStatus(String.valueOf(unfollowEventId))).isEqualTo("PROCESSED");
                });
    }

    @Test
    void block_shouldPreventFutureFollowAndFlowThroughMqListener() throws Exception {
        TestSession target = registerAndLoginSession("blocked");
        TestSession source = registerAndLoginSession("blocker");

        ensureRelationTopology();
        ensureRelationConsumersReady();

        assertSuccess(postJson("/api/v1/relation/follow", JsonNodeFactory.instance.objectNode()
                .put("targetId", target.userId()), source.token()));

        assertSuccess(postJson("/api/v1/relation/block", JsonNodeFactory.instance.objectNode()
                .put("targetId", target.userId()), source.token()));

        var stateBatchReq = JsonNodeFactory.instance.objectNode();
        stateBatchReq.putArray("targetUserIds").add(target.userId());
        JsonNode stateBatch = assertSuccess(postJson("/api/v1/relation/state/batch", stateBatchReq, source.token()));
        assertThat(stateBatch.path("blockedUserIds")).extracting(JsonNode::asLong).contains(target.userId());
        assertThat(stateBatch.path("followingUserIds")).isEmpty();

        JsonNode followAfterBlock = assertSuccess(postJson("/api/v1/relation/follow", JsonNodeFactory.instance.objectNode()
                .put("targetId", target.userId()), source.token()));
        assertThat(followAfterBlock.path("status").asText()).isEqualTo("BLOCKED");

        Long blockEventId = latestRelationOutboxEventId("BLOCK");
        assertThat(blockEventId).isNotNull();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                {
                    publishPendingRelationEvents();
                    assertThat(relationOutboxStatus(blockEventId)).isEqualTo("DONE");
                    assertThat(relationInboxStatus(String.valueOf(blockEventId))).isEqualTo("PROCESSED");
                });
    }

    private void ensureRelationTopology() {
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare(RelationMqConfig.EXCHANGE, "direct", true);
            channel.queueDeclare(RelationMqConfig.Q_FOLLOW, true, false, false, null);
            channel.queueDeclare(RelationMqConfig.Q_BLOCK, true, false, false, null);
            channel.queueBind(RelationMqConfig.Q_FOLLOW, RelationMqConfig.EXCHANGE, RelationMqConfig.RK_FOLLOW);
            channel.queueBind(RelationMqConfig.Q_BLOCK, RelationMqConfig.EXCHANGE, RelationMqConfig.RK_BLOCK);
            return null;
        });
    }

    private void ensureRelationConsumersReady() {
        // 真实链路测试依赖 MQ listener 已就绪，否则 publish 会变成“无消费者 + 20s 超时”的随机失败。
        startRabbitListenerContainers();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(queueConsumerCount(RelationMqConfig.Q_FOLLOW)).isGreaterThan(0);
            assertThat(queueConsumerCount(RelationMqConfig.Q_BLOCK)).isGreaterThan(0);
        });
    }

    private void startRabbitListenerContainers() {
        rabbitListenerEndpointRegistry.getListenerContainers().forEach(container -> {
            try {
                if (!container.isRunning()) {
                    container.start();
                }
            } catch (Exception ignored) {
                // 让后续 consumerCount 断言暴露真实问题。
            }
        });
    }

    private long queueConsumerCount(String queueName) {
        return rabbitTemplate.execute(channel -> channel.consumerCount(queueName));
    }

    private long seedPublishedPost(long authorId) {
        long postId = uniqueId();
        Date now = new Date();
        ContentPostPO post = new ContentPostPO();
        post.setPostId(postId);
        post.setUserId(authorId);
        post.setTitle("relation-followee-post-" + postId);
        post.setContentUuid(uniqueUuid());
        post.setSummary("relation integration");
        post.setSummaryStatus(1);
        post.setMediaType(0);
        post.setStatus(ContentPostStatusEnumVO.PUBLISHED.getCode());
        post.setVisibility(ContentPostVisibilityEnumVO.PUBLIC.getCode());
        post.setVersionNum(1);
        post.setIsEdited(0);
        post.setCreateTime(now);
        post.setPublishTime(now);
        contentPostDao.insert(post);
        postContentKvPort.add(post.getContentUuid(), "relation post content " + postId);
        return postId;
    }

    private Long latestRelationOutboxEventId(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return null;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT event_id FROM relation_event_outbox WHERE event_type = ? ORDER BY create_time DESC LIMIT 1")) {
            ps.setString(1, eventType.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long id = rs.getLong(1);
                return id <= 0 ? null : id;
            }
        } catch (Exception e) {
            throw new IllegalStateException("query relation_event_outbox failed, eventType=" + eventType, e);
        }
    }

    private String relationInboxStatus(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM relation_event_inbox WHERE fingerprint = ?")) {
            ps.setString(1, fingerprint.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query relation_event_inbox failed, fingerprint=" + fingerprint, e);
        }
    }

    private String relationOutboxStatus(Long eventId) {
        if (eventId == null || eventId <= 0) {
            return null;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM relation_event_outbox WHERE event_id = ?")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query relation_event_outbox failed, eventId=" + eventId, e);
        }
    }

    private void publishPendingRelationEvents() {
        execUpdate("UPDATE relation_event_outbox SET next_retry_time = NOW() WHERE status IN ('NEW','FAIL')");
        relationEventOutboxPublishJob.publishPending();
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
