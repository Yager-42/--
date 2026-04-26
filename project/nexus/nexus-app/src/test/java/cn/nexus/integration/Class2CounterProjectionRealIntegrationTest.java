package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import cn.nexus.infrastructure.dao.social.IClass2UserCounterRepairTaskDao;
import cn.nexus.infrastructure.dao.social.IRelationEventOutboxDao;
import cn.nexus.infrastructure.dao.social.IReliableMqReplayRecordDao;
import cn.nexus.infrastructure.dao.social.po.RelationEventOutboxPO;
import cn.nexus.infrastructure.dao.social.po.ReliableMqReplayRecordPO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import cn.nexus.trigger.job.social.Class2UserCounterRepairJob;
import cn.nexus.trigger.job.social.RelationEventOutboxPublishJob;
import cn.nexus.trigger.job.social.ReliableMqReplayJob;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

class Class2CounterProjectionRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @Autowired
    private RelationEventOutboxPublishJob relationEventOutboxPublishJob;

    @Autowired
    private Class2UserCounterRepairJob class2UserCounterRepairJob;

    @Autowired
    private ReliableMqReplayJob reliableMqReplayJob;

    @Autowired
    private IRelationEventOutboxDao relationEventOutboxDao;

    @Autowired
    private IClass2UserCounterRepairTaskDao repairTaskDao;

    @Autowired
    private IReliableMqReplayRecordDao replayRecordDao;

    @Test
    void followThenRapidUnfollowThenRefollow_shouldConvergeToSingleFollower() throws Exception {
        TestSession followee = registerAndLoginSession("c2-followee");
        TestSession follower = registerAndLoginSession("c2-follower");
        ensureRelationTopology();
        ensureRelationConsumersReady();

        assertSuccess(postJson("/api/v1/relation/follow", JsonNodeFactory.instance.objectNode()
                .put("targetId", followee.userId()), follower.token()));
        assertSuccess(postJson("/api/v1/relation/unfollow", JsonNodeFactory.instance.objectNode()
                .put("targetId", followee.userId()), follower.token()));
        assertSuccess(postJson("/api/v1/relation/follow", JsonNodeFactory.instance.objectNode()
                .put("targetId", followee.userId()), follower.token()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingRelationEvents();
            assertThat(readUserSnapshotCount(follower.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING))
                    .isEqualTo(1L);
            assertThat(readUserSnapshotCount(followee.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWER))
                    .isEqualTo(1L);
        });
    }

    @Test
    void blockDeliveredBeforeFollowUnfollow_shouldStillConvergeCounters() throws Exception {
        TestSession userA = registerAndLoginSession("c2-block-oo-a");
        TestSession userB = registerAndLoginSession("c2-block-oo-b");
        ensureRelationTopology();
        ensureRelationConsumersReady();

        long eventBase = uniqueId();
        RelationCounterProjectEvent followActive = followEvent(eventBase + 1, userA.userId(), userB.userId(), "ACTIVE", 1L);
        RelationCounterProjectEvent followUnfollow = followEvent(eventBase + 2, userA.userId(), userB.userId(), "UNFOLLOW", 2L);
        RelationCounterProjectEvent block = blockEvent(eventBase + 3, userA.userId(), userB.userId(), 3L);

        rabbitTemplate.convertAndSend(RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_BLOCK, block);
        rabbitTemplate.convertAndSend(RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_FOLLOW, followActive);
        rabbitTemplate.convertAndSend(RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_FOLLOW, followUnfollow);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(readUserSnapshotCount(userA.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING))
                    .isEqualTo(0L);
            assertThat(readUserSnapshotCount(userB.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWER))
                    .isEqualTo(0L);
        });
    }

    @Test
    void duplicateFollowProjectionDelivery_shouldNotDoubleIncrementCounters() throws Exception {
        TestSession followee = registerAndLoginSession("c2-dup-followee");
        TestSession follower = registerAndLoginSession("c2-dup-follower");
        ensureRelationTopology();
        ensureRelationConsumersReady();

        assertSuccess(postJson("/api/v1/relation/follow", JsonNodeFactory.instance.objectNode()
                .put("targetId", followee.userId()), follower.token()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(this::publishPendingRelationEvents);

        Long eventId = latestRelationOutboxEventId("FOLLOW");
        String payload = relationOutboxPayload(eventId);
        RelationCounterProjectEvent event = objectMapper.readValue(payload, RelationCounterProjectEvent.class);
        event.setEventType("FOLLOW");
        event.setEventId("relation-counter:" + eventId);
        rabbitTemplate.convertAndSend(RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_FOLLOW, event);
        rabbitTemplate.convertAndSend(RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_FOLLOW, event);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(readUserSnapshotCount(follower.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING))
                    .isEqualTo(1L);
            assertThat(readUserSnapshotCount(followee.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWER))
                    .isEqualTo(1L);
        });
    }

    @Test
    void postPublishThenUnpublish_shouldConvergePostCount() throws Exception {
        TestSession author = registerAndLoginSession("c2-post-author");
        ensureRelationTopology();
        ensureRelationConsumersReady();

        long postId = assertSuccess(putJson("/api/v1/content/draft", JsonNodeFactory.instance.objectNode()
                .put("title", "p-" + uniqueUuid().substring(0, 6))
                .put("contentText", "body-" + uniqueUuid()), author.token()))
                .path("draftId").asLong();

        assertSuccess(postJson("/api/v1/content/publish", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("title", "pub-" + uniqueUuid().substring(0, 6))
                .put("text", "text-" + uniqueUuid())
                .put("visibility", "PUBLIC"), author.token()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(this::publishPendingRelationEvents);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(readUserSnapshotCount(author.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.POST))
                        .isEqualTo(1L));

        assertSuccess(deleteJson("/api/v1/content/" + postId, JsonNodeFactory.instance.objectNode(), author.token()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(this::publishPendingRelationEvents);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(readUserSnapshotCount(author.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.POST))
                        .isEqualTo(0L));
    }

    @Test
    void redisDeltaAppliedThenConsumerTransactionRolledBack_shouldNotDoubleIncrementOnReplay() throws Exception {
        TestSession followee = registerAndLoginSession("c2-rollback-followee");
        TestSession follower = registerAndLoginSession("c2-rollback-follower");
        ensureRelationTopology();
        ensureRelationConsumersReady();

        long eventId = uniqueId();
        RelationCounterProjectEvent broken = followEvent(eventId, follower.userId(), followee.userId(), "ACTIVE", null);
        rabbitTemplate.convertAndSend(RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_FOLLOW, broken);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            ReliableMqReplayRecordPO replay = replayRecordByEvent("relation-counter:" + eventId);
            assertThat(replay).isNotNull();
            assertThat(replay.getStatus()).isIn("PENDING", "RETRY_PENDING");
        });
        assertThat(readUserSnapshotCount(follower.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING))
                .isEqualTo(0L);
        assertThat(readUserSnapshotCount(followee.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWER))
                .isEqualTo(0L);

        RelationCounterProjectEvent fixed = followEvent(eventId, follower.userId(), followee.userId(), "ACTIVE", 10L);
        rabbitTemplate.convertAndSend(RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_FOLLOW, fixed);
        rabbitTemplate.convertAndSend(RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_FOLLOW, fixed);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(readUserSnapshotCount(follower.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING))
                    .isEqualTo(1L);
            assertThat(readUserSnapshotCount(followee.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWER))
                    .isEqualTo(1L);
        });
    }

    @Test
    void repairTask_shouldCorrectRedisDriftAfterInjectedProjectionFailure() throws Exception {
        TestSession followee = registerAndLoginSession("c2-repair-followee");
        TestSession follower = registerAndLoginSession("c2-repair-follower");
        ensureRelationTopology();
        ensureRelationConsumersReady();

        assertSuccess(postJson("/api/v1/relation/follow", JsonNodeFactory.instance.objectNode()
                .put("targetId", followee.userId()), follower.token()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(this::publishPendingRelationEvents);

        writeUserSnapshot(follower.userId(), new long[]{0L, 0L, 0L, 0L, 0L});
        deleteRedisKey("ucnt:chk:" + follower.userId());
        assertSuccess(getJson("/api/v1/relation/counter", follower.token()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repairTaskCountByUser(follower.userId())).isGreaterThanOrEqualTo(1));
        class2UserCounterRepairJob.repairPending();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(readUserSnapshotCount(follower.userId(), cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING))
                        .isEqualTo(1L));
    }

    @Test
    void repairWorkerCrashAfterClaim_shouldBeReclaimedAfterLeaseExpiry() {
        String dedupe = "USER_CLASS2:" + uniqueId();
        long userId = uniqueId();
        Date now = new Date();
        repairTaskDao.insertIgnore(
                uniqueId(),
                "USER_CLASS2",
                userId,
                dedupe,
                "RUNNING",
                1,
                now,
                "simulated crash",
                now,
                now);
        execUpdate("UPDATE class2_user_counter_repair_task SET claim_owner='worker-a', lease_until=DATE_SUB(NOW(), INTERVAL 5 SECOND) WHERE dedupe_key='"
                + dedupe + "'");

        class2UserCounterRepairJob.repairPending();
        assertThat(repairTaskDoneCountByUser(userId)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void multiWorkerClaim_shouldNotExecuteSameRepairTaskTwice() throws Exception {
        long userId = uniqueId();
        String dedupe = "USER_CLASS2:" + userId;
        Date now = new Date();
        repairTaskDao.insertIgnore(
                uniqueId(),
                "USER_CLASS2",
                userId,
                dedupe,
                "PENDING",
                0,
                now,
                "multi-worker race",
                now,
                now);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> workerA = CompletableFuture.runAsync(class2UserCounterRepairJob::repairPending, pool);
            CompletableFuture<Void> workerB = CompletableFuture.runAsync(class2UserCounterRepairJob::repairPending, pool);
            CompletableFuture.allOf(workerA, workerB).join();
        } finally {
            pool.shutdownNow();
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repairTaskDoneCountByUser(userId)).isEqualTo(1L));
    }

    @Test
    void repairTask_doneThenDriftAgain_shouldBeEnqueuedAgain() {
        long userId = uniqueId();
        String dedupe = "USER_CLASS2:" + userId;
        Date now = new Date();
        repairTaskDao.insertIgnore(
                uniqueId(),
                "USER_CLASS2",
                userId,
                dedupe,
                "PENDING",
                0,
                now,
                "first drift",
                now,
                now);

        class2UserCounterRepairJob.repairPending();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repairTaskDoneCountByUser(userId)).isGreaterThanOrEqualTo(1));

        execUpdate("UPDATE class2_user_counter_repair_task SET status='DONE', claim_owner=NULL, claimed_at=NULL, lease_until=NULL, update_time=NOW() WHERE dedupe_key='"
                + dedupe + "'");
        repairTaskDao.insertIgnore(
                uniqueId(),
                "USER_CLASS2",
                userId,
                dedupe,
                "PENDING",
                0,
                new Date(),
                "second drift",
                new Date(),
                new Date());

        class2UserCounterRepairJob.repairPending();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repairTaskDoneCountByUser(userId)).isGreaterThanOrEqualTo(2));
    }

    @Test
    void deadLetteredProjection_shouldBeReplayableThroughReliableMqReplayJob() throws Exception {
        ensureRelationTopology();
        ensureRelationConsumersReady();
        stopRelationConsumers();

        long eventId = uniqueId();
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + eventId);
        event.setRelationEventId(eventId);
        event.setEventType("FOLLOW");
        event.setSourceId(uniqueId());
        event.setTargetId(uniqueId());
        event.setStatus("ACTIVE");
        event.setProjectionKey("follow:" + event.getSourceId() + ":" + event.getTargetId());
        event.setProjectionVersion(1L);
        rabbitTemplate.convertAndSend(RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_FOLLOW, event);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            ReliableMqReplayRecordPO record = replayRecordByEvent("relation-counter:" + eventId);
            assertThat(record).isNotNull();
            assertThat(record.getStatus()).isIn("PENDING", "RETRY_PENDING");
        });

        startRelationConsumers();
        reliableMqReplayJob.replayReady();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            ReliableMqReplayRecordPO record = replayRecordByEvent("relation-counter:" + eventId);
            assertThat(record).isNotNull();
            assertThat(record.getStatus()).isEqualTo("DONE");
        });
    }

    private void ensureRelationTopology() {
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare(RelationCounterRouting.EXCHANGE, "direct", true);
            channel.exchangeDeclare(RelationCounterRouting.DLX_EXCHANGE, "direct", true);
            channel.queueDeclare(RelationCounterRouting.Q_FOLLOW, true, false, false, java.util.Map.of(
                    "x-dead-letter-exchange", RelationCounterRouting.DLX_EXCHANGE,
                    "x-dead-letter-routing-key", RelationCounterRouting.RK_FOLLOW_DLX));
            channel.queueDeclare(RelationCounterRouting.Q_BLOCK, true, false, false, java.util.Map.of(
                    "x-dead-letter-exchange", RelationCounterRouting.DLX_EXCHANGE,
                    "x-dead-letter-routing-key", RelationCounterRouting.RK_BLOCK_DLX));
            channel.queueDeclare(RelationCounterRouting.Q_POST, true, false, false, java.util.Map.of(
                    "x-dead-letter-exchange", RelationCounterRouting.DLX_EXCHANGE,
                    "x-dead-letter-routing-key", RelationCounterRouting.RK_POST_DLX));
            channel.queueDeclare(RelationCounterRouting.DLQ_FOLLOW, true, false, false, null);
            channel.queueDeclare(RelationCounterRouting.DLQ_BLOCK, true, false, false, null);
            channel.queueDeclare(RelationCounterRouting.DLQ_POST, true, false, false, null);
            channel.queueBind(RelationCounterRouting.Q_FOLLOW, RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_FOLLOW);
            channel.queueBind(RelationCounterRouting.Q_BLOCK, RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_BLOCK);
            channel.queueBind(RelationCounterRouting.Q_POST, RelationCounterRouting.EXCHANGE, RelationCounterRouting.RK_POST);
            channel.queueBind(RelationCounterRouting.DLQ_FOLLOW, RelationCounterRouting.DLX_EXCHANGE, RelationCounterRouting.RK_FOLLOW_DLX);
            channel.queueBind(RelationCounterRouting.DLQ_BLOCK, RelationCounterRouting.DLX_EXCHANGE, RelationCounterRouting.RK_BLOCK_DLX);
            channel.queueBind(RelationCounterRouting.DLQ_POST, RelationCounterRouting.DLX_EXCHANGE, RelationCounterRouting.RK_POST_DLX);
            return null;
        });
    }

    private void ensureRelationConsumersReady() {
        startRelationConsumers();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(queueConsumerCount(RelationCounterRouting.Q_FOLLOW)).isGreaterThan(0);
            assertThat(queueConsumerCount(RelationCounterRouting.Q_BLOCK)).isGreaterThan(0);
            assertThat(queueConsumerCount(RelationCounterRouting.Q_POST)).isGreaterThan(0);
        });
    }

    private void startRelationConsumers() {
        rabbitListenerEndpointRegistry.getListenerContainers().forEach(container -> {
            if (!isRelationCounterContainer(container)) {
                return;
            }
            if (!container.isRunning()) {
                container.start();
            }
        });
    }

    private void stopRelationConsumers() {
        rabbitListenerEndpointRegistry.getListenerContainers().forEach(container -> {
            if (!isRelationCounterContainer(container)) {
                return;
            }
            if (container.isRunning()) {
                container.stop();
            }
        });
    }

    private boolean isRelationCounterContainer(org.springframework.amqp.rabbit.listener.MessageListenerContainer container) {
        if (!(container instanceof org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer c)) {
            return false;
        }
        String[] queueNames = c.getQueueNames();
        if (queueNames == null || queueNames.length == 0) {
            return false;
        }
        for (String queueName : queueNames) {
            if (RelationCounterRouting.Q_FOLLOW.equals(queueName)
                    || RelationCounterRouting.Q_BLOCK.equals(queueName)
                    || RelationCounterRouting.Q_POST.equals(queueName)) {
                return true;
            }
        }
        return false;
    }

    private long queueConsumerCount(String queueName) {
        return rabbitTemplate.execute(channel -> channel.consumerCount(queueName));
    }

    private void publishPendingRelationEvents() {
        execUpdate("UPDATE relation_event_outbox SET next_retry_time = NOW() WHERE status IN ('NEW','FAIL')");
        relationEventOutboxPublishJob.publishPending();
    }

    private Long latestRelationOutboxEventId(String eventType) {
        List<RelationEventOutboxPO> list = relationEventOutboxDao.selectByStatus("DONE", new Date(), 1000);
        Long latest = null;
        for (RelationEventOutboxPO po : list) {
            if (po == null || po.getEventType() == null || !eventType.equalsIgnoreCase(po.getEventType())) {
                continue;
            }
            if (latest == null || (po.getEventId() != null && po.getEventId() > latest)) {
                latest = po.getEventId();
            }
        }
        return latest;
    }

    private String relationOutboxPayload(Long eventId) {
        if (eventId == null) {
            return null;
        }
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement("SELECT payload FROM relation_event_outbox WHERE event_id = ?")) {
            ps.setLong(1, eventId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query relation outbox payload failed, eventId=" + eventId, e);
        }
    }

    private long repairTaskCountByUser(Long userId) {
        if (userId == null) {
            return 0L;
        }
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(1) FROM class2_user_counter_repair_task WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query repair task count failed, userId=" + userId, e);
        }
    }

    private long repairTaskDoneCountByUser(Long userId) {
        if (userId == null) {
            return 0L;
        }
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(1) FROM class2_user_counter_repair_task WHERE user_id = ? AND status = 'DONE'")) {
            ps.setLong(1, userId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query repair task done count failed, userId=" + userId, e);
        }
    }

    private ReliableMqReplayRecordPO replayRecordByEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        List<ReliableMqReplayRecordPO> list = replayRecordDao.selectReady(new Date(System.currentTimeMillis() + 60_000L), 1000);
        for (ReliableMqReplayRecordPO po : list) {
            if (po != null && eventId.equals(po.getEventId())) {
                return po;
            }
        }
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, event_id, consumer_name, original_queue, original_exchange, original_routing_key, payload_type, payload_json, status, attempt, next_retry_at, last_error, create_time, update_time FROM reliable_mq_replay_record WHERE event_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, eventId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                ReliableMqReplayRecordPO po = new ReliableMqReplayRecordPO();
                po.setId(rs.getLong("id"));
                po.setEventId(rs.getString("event_id"));
                po.setConsumerName(rs.getString("consumer_name"));
                po.setOriginalQueue(rs.getString("original_queue"));
                po.setOriginalExchange(rs.getString("original_exchange"));
                po.setOriginalRoutingKey(rs.getString("original_routing_key"));
                po.setPayloadType(rs.getString("payload_type"));
                po.setPayloadJson(rs.getString("payload_json"));
                po.setStatus(rs.getString("status"));
                po.setAttempt(rs.getInt("attempt"));
                po.setNextRetryAt(rs.getTimestamp("next_retry_at"));
                po.setLastError(rs.getString("last_error"));
                po.setCreateTime(rs.getTimestamp("create_time"));
                po.setUpdateTime(rs.getTimestamp("update_time"));
                return po;
            }
        } catch (Exception e) {
            throw new IllegalStateException("query replay record failed, eventId=" + eventId, e);
        }
    }

    private void writeUserSnapshot(long userId, long[] slots) {
        writeRawRedisValue("ucnt:" + userId, CountRedisCodec.encodeSlots(slots, 5));
    }

    private void writeRawRedisValue(String key, byte[] payload) {
        stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) connection -> {
            if (connection == null || connection.stringCommands() == null) {
                return false;
            }
            connection.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), payload);
            return true;
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

    private RelationCounterProjectEvent followEvent(long relationEventId,
                                                    long sourceId,
                                                    long targetId,
                                                    String status,
                                                    Long projectionVersion) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + relationEventId);
        event.setRelationEventId(relationEventId);
        event.setEventType("FOLLOW");
        event.setSourceId(sourceId);
        event.setTargetId(targetId);
        event.setStatus(status);
        event.setProjectionKey("follow:" + sourceId + ":" + targetId);
        event.setProjectionVersion(projectionVersion);
        return event;
    }

    private RelationCounterProjectEvent blockEvent(long relationEventId,
                                                   long sourceId,
                                                   long targetId,
                                                   Long projectionVersion) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + relationEventId);
        event.setRelationEventId(relationEventId);
        event.setEventType("BLOCK");
        event.setSourceId(sourceId);
        event.setTargetId(targetId);
        event.setProjectionKey("block:" + sourceId + ":" + targetId);
        event.setProjectionVersion(projectionVersion);
        return event;
    }
}
