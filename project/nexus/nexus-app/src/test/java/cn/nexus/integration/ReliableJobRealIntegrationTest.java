package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.infrastructure.dao.social.ICommentDao;
import cn.nexus.infrastructure.dao.social.IContentEventOutboxDao;
import cn.nexus.infrastructure.dao.social.IRelationEventInboxDao;
import cn.nexus.infrastructure.dao.social.IRelationEventOutboxDao;
import cn.nexus.infrastructure.dao.social.IReliableMqOutboxDao;
import cn.nexus.infrastructure.dao.social.IReliableMqReplayRecordDao;
import cn.nexus.infrastructure.dao.social.po.CommentPO;
import cn.nexus.infrastructure.dao.social.po.ContentEventOutboxPO;
import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.infrastructure.dao.social.po.RelationEventInboxPO;
import cn.nexus.infrastructure.dao.social.po.RelationEventOutboxPO;
import cn.nexus.infrastructure.dao.social.po.ReliableMqOutboxPO;
import cn.nexus.infrastructure.dao.social.po.ReliableMqReplayRecordPO;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import cn.nexus.infrastructure.dao.social.po.UserPrivacyPO;
import cn.nexus.infrastructure.dao.user.IUserEventOutboxDao;
import cn.nexus.infrastructure.dao.user.po.UserEventOutboxPO;
import cn.nexus.infrastructure.dao.user.po.UserStatusPO;
import cn.nexus.infrastructure.adapter.social.port.RelationFollowEvent;
import cn.nexus.trigger.job.social.CommentSoftDeleteCleanupJob;
import cn.nexus.trigger.job.social.ContentEventOutboxRetryJob;
import cn.nexus.trigger.job.social.ContentSoftDeleteCleanupJob;
import cn.nexus.trigger.job.social.RelationEventOutboxPublishJob;
import cn.nexus.trigger.job.social.RelationEventRetryJob;
import cn.nexus.trigger.job.social.ReliableMqOutboxRetryJob;
import cn.nexus.trigger.job.social.ReliableMqReplayJob;
import cn.nexus.trigger.job.user.UserEventOutboxRetryJob;
import cn.nexus.trigger.mq.config.RelationMqConfig;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import cn.nexus.types.event.PostPublishedEvent;
import cn.nexus.types.event.UserNicknameChangedEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "content.cleanup.softDeleteRetentionDays=1",
        "comment.cleanup.softDeleteRetentionDays=1"
})
class ReliableJobRealIntegrationTest extends RealMiddlewareIntegrationTestSupport {

    @Autowired
    private ContentEventOutboxRetryJob contentEventOutboxRetryJob;

    @Autowired
    private ReliableMqOutboxRetryJob reliableMqOutboxRetryJob;

    @Autowired
    private ReliableMqReplayJob reliableMqReplayJob;

    @Autowired
    private UserEventOutboxRetryJob userEventOutboxRetryJob;

    @Autowired
    private ContentSoftDeleteCleanupJob contentSoftDeleteCleanupJob;

    @Autowired
    private CommentSoftDeleteCleanupJob commentSoftDeleteCleanupJob;

    @Autowired
    private RelationEventOutboxPublishJob relationEventOutboxPublishJob;

    @Autowired
    private RelationEventRetryJob relationEventRetryJob;

    @Autowired
    private IContentEventOutboxDao contentEventOutboxDao;

    @Autowired
    private IRelationEventOutboxDao relationEventOutboxDao;

    @Autowired
    private IRelationEventInboxDao relationEventInboxDao;

    @Autowired
    private IReliableMqOutboxDao reliableMqOutboxDao;

    @Autowired
    private IReliableMqReplayRecordDao reliableMqReplayRecordDao;

    @Autowired
    private IUserEventOutboxDao userEventOutboxDao;

    @Autowired
    private ICommentDao commentDao;

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @Test
    void contentEventOutboxRetryJob_shouldMoveNewRecordToSent() throws Exception {
        long postId = uniqueId();
        PostPublishedEvent event = new PostPublishedEvent();
        event.setEventId("content-job-it-" + uniqueUuid());
        event.setPostId(postId);
        event.setAuthorId(uniqueId());
        event.setPublishTimeMs(System.currentTimeMillis());

        ContentEventOutboxPO po = new ContentEventOutboxPO();
        po.setEventId("post.published:" + postId + ":1");
        po.setEventType("post.published");
        po.setPayloadJson(objectMapper.writeValueAsString(event));
        po.setStatus("NEW");
        po.setRetryCount(0);
        po.setNextRetryTime(new Date(System.currentTimeMillis() - 1000));
        contentEventOutboxDao.insertIgnore(po);

        contentEventOutboxRetryJob.retryPending();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(contentEventOutboxStatus(po.getEventId())).isEqualTo("SENT"));
    }

    @Test
    void reliableMqOutboxRetryJob_shouldMovePendingRecordToSent() throws Exception {
        PostPublishedEvent event = new PostPublishedEvent();
        event.setEventId("reliable-outbox-it-" + uniqueUuid());
        event.setPostId(uniqueId());
        event.setAuthorId(uniqueId());
        event.setPublishTimeMs(System.currentTimeMillis());

        ReliableMqOutboxPO po = new ReliableMqOutboxPO();
        po.setEventId(event.getEventId());
        po.setExchangeName("");
        po.setRoutingKey("");
        po.setPayloadType(PostPublishedEvent.class.getName());
        po.setPayloadJson(objectMapper.writeValueAsString(event));
        po.setHeadersJson("{}");
        po.setStatus("PENDING");
        po.setRetryCount(0);
        po.setNextRetryAt(new Date(System.currentTimeMillis() - 1000));
        reliableMqOutboxDao.insertIgnore(po);

        reliableMqOutboxRetryJob.publishReady();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(reliableMqOutboxStatus(event.getEventId())).isEqualTo("SENT"));
    }

    @Test
    void reliableMqReplayJob_shouldMovePendingReplayRecordToDone() throws Exception {
        PostPublishedEvent event = new PostPublishedEvent();
        event.setEventId("reliable-replay-it-" + uniqueUuid());
        event.setPostId(uniqueId());
        event.setAuthorId(uniqueId());
        event.setPublishTimeMs(System.currentTimeMillis());

        ReliableMqReplayRecordPO po = new ReliableMqReplayRecordPO();
        po.setEventId(event.getEventId());
        po.setConsumerName("job-real-it");
        po.setOriginalQueue("job.real.it.queue");
        po.setOriginalExchange("");
        po.setOriginalRoutingKey("");
        po.setPayloadType(PostPublishedEvent.class.getName());
        po.setPayloadJson(objectMapper.writeValueAsString(event));
        po.setStatus("PENDING");
        po.setAttempt(0);
        po.setNextRetryAt(new Date(System.currentTimeMillis() - 1000));
        reliableMqReplayRecordDao.insertIgnore(po);

        reliableMqReplayJob.replayReady();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(reliableMqReplayStatus(event.getEventId())).isEqualTo("DONE"));
    }

    @Test
    void userEventOutboxRetryJob_shouldMoveNewRecordToDone() throws Exception {
        UserNicknameChangedEvent event = new UserNicknameChangedEvent();
        event.setEventId("user-outbox-it-" + uniqueUuid());
        event.setUserId(uniqueId());
        event.setTsMs(System.currentTimeMillis());

        UserEventOutboxPO po = new UserEventOutboxPO();
        po.setEventType("user.nickname_changed");
        po.setFingerprint("user.nickname_changed:" + event.getUserId() + ":" + event.getTsMs());
        po.setPayload(objectMapper.writeValueAsString(event));
        po.setStatus("NEW");
        po.setRetryCount(0);
        userEventOutboxDao.insertIgnore(po);

        userEventOutboxRetryJob.retryPending();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(userEventOutboxStatus(po.getFingerprint())).isEqualTo("DONE"));
    }

    @Test
    void relationEventOutboxPublishJob_shouldPublishFollowEventAndMarkOutboxDone() throws Exception {
        long followeeId = uniqueId();
        long followerId = uniqueId();
        seedRelationUser(followeeId);
        seedRelationUser(followerId);
        long postId = seedPublishedPost(followeeId);

        clearFeedKeys(followeeId, followerId);
        feedTimelineRepository.replaceInbox(followerId, List.of());
        ensureRelationTopology();
        ensureRelationConsumersReady();

        long eventId = uniqueId();
        RelationEventOutboxPO po = new RelationEventOutboxPO();
        po.setEventId(eventId);
        po.setEventType("FOLLOW");
        po.setPayload(objectMapper.writeValueAsString(new RelationFollowEvent(eventId, followerId, followeeId, "ACTIVE")));
        po.setStatus("NEW");
        po.setRetryCount(0);
        po.setNextRetryTime(new Date(System.currentTimeMillis() - 1000));
        relationEventOutboxDao.insertIgnore(po);

        relationEventOutboxPublishJob.publishPending();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(relationOutboxStatus(eventId)).isEqualTo("DONE");
            assertThat(relationInboxStatus(String.valueOf(eventId))).isEqualTo("PROCESSED");
            assertThat(inboxEntries(followerId, 20))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
        });
    }

    @Test
    void relationEventRetryJob_shouldReplayFailedFollowInboxAndRestoreCompensation() throws Exception {
        long followeeId = uniqueId();
        long followerId = uniqueId();
        seedRelationUser(followeeId);
        seedRelationUser(followerId);
        long postId = seedPublishedPost(followeeId);

        clearFeedKeys(followeeId, followerId);
        feedTimelineRepository.replaceInbox(followerId, List.of());
        ensureRelationTopology();
        ensureRelationConsumersReady();

        long eventId = uniqueId();
        RelationEventInboxPO po = new RelationEventInboxPO();
        po.setEventType("FOLLOW");
        po.setFingerprint(String.valueOf(eventId));
        po.setPayload(objectMapper.writeValueAsString(new RelationFollowEvent(eventId, followerId, followeeId, "ACTIVE")));
        po.setStatus("FAILED");
        relationEventInboxDao.insertIgnore(po);

        relationEventRetryJob.retryFailed();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(relationInboxStatus(po.getFingerprint())).isEqualTo("PROCESSED");
            assertThat(inboxEntries(followerId, 20))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
        });
    }

    @Test
    void contentSoftDeleteCleanupJob_shouldDeleteExpiredSoftDeletedPost() {
        long postId = uniqueId();
        Date oldTime = new Date(System.currentTimeMillis() - Duration.ofDays(3).toMillis());

        ContentPostPO post = new ContentPostPO();
        post.setPostId(postId);
        post.setUserId(uniqueId());
        post.setTitle("cleanup-post-" + postId);
        post.setContentUuid(uniqueUuid());
        post.setSummary("cleanup");
        post.setSummaryStatus(1);
        post.setMediaType(0);
        post.setStatus(ContentPostStatusEnumVO.DELETED.getCode());
        post.setVisibility(ContentPostVisibilityEnumVO.PUBLIC.getCode());
        post.setVersionNum(1);
        post.setIsEdited(0);
        post.setCreateTime(oldTime);
        post.setPublishTime(oldTime);
        contentPostDao.insert(post);
        execUpdate("UPDATE content_post SET update_time = DATE_SUB(NOW(), INTERVAL 3 DAY), delete_time = DATE_SUB(NOW(), INTERVAL 3 DAY) WHERE post_id = " + postId);

        contentSoftDeleteCleanupJob.cleanSoftDeletedPosts();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(countBySql("SELECT COUNT(1) FROM content_post WHERE post_id = " + postId)).isZero());
    }

    @Test
    void commentSoftDeleteCleanupJob_shouldDeleteExpiredSoftDeletedComment() {
        long commentId = uniqueId();
        Date oldTime = new Date(System.currentTimeMillis() - Duration.ofDays(3).toMillis());

        CommentPO comment = new CommentPO();
        comment.setCommentId(commentId);
        comment.setPostId(uniqueId());
        comment.setUserId(uniqueId());
        comment.setRootId(0L);
        comment.setParentId(0L);
        comment.setReplyToId(0L);
        comment.setContentId(uniqueUuid());
        comment.setStatus(2);
        comment.setLikeCount(0L);
        comment.setReplyCount(0L);
        comment.setCreateTime(oldTime);
        comment.setUpdateTime(oldTime);
        commentDao.insert(comment);
        execUpdate("UPDATE interaction_comment SET update_time = DATE_SUB(NOW(), INTERVAL 3 DAY) WHERE comment_id = " + commentId);

        commentSoftDeleteCleanupJob.cleanSoftDeletedComments();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(countBySql("SELECT COUNT(1) FROM interaction_comment WHERE comment_id = " + commentId)).isZero());
    }

    private String contentEventOutboxStatus(String eventId) {
        return querySingleString("SELECT status FROM content_event_outbox WHERE event_id = ?", eventId);
    }

    private String reliableMqOutboxStatus(String eventId) {
        return querySingleString("SELECT status FROM reliable_mq_outbox WHERE event_id = ?", eventId);
    }

    private String reliableMqReplayStatus(String eventId) {
        return querySingleString("SELECT status FROM reliable_mq_replay_record WHERE event_id = ?", eventId);
    }

    private String userEventOutboxStatus(String fingerprint) {
        return querySingleString("SELECT status FROM user_event_outbox WHERE fingerprint = ?", fingerprint);
    }

    private String relationOutboxStatus(Long eventId) {
        return eventId == null ? null : querySingleString("SELECT status FROM relation_event_outbox WHERE event_id = ?", eventId);
    }

    private String relationInboxStatus(String fingerprint) {
        return querySingleString("SELECT status FROM relation_event_inbox WHERE fingerprint = ?", fingerprint);
    }

    private String querySingleString(String sql, Long value) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query failed: " + sql + ", value=" + value, e);
        }
    }

    private String querySingleString(String sql, String value) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query failed: " + sql + ", value=" + value, e);
        }
    }

    private long countBySql(String sql) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return 0L;
            }
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("count sql failed: " + sql, e);
        }
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
                // Consumer count assertions expose real listener startup issues.
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
        post.setTitle("relation-job-post-" + postId);
        post.setContentUuid(uniqueUuid());
        post.setSummary("relation job integration");
        post.setSummaryStatus(1);
        post.setMediaType(0);
        post.setStatus(ContentPostStatusEnumVO.PUBLISHED.getCode());
        post.setVisibility(ContentPostVisibilityEnumVO.PUBLIC.getCode());
        post.setVersionNum(1);
        post.setIsEdited(0);
        post.setCreateTime(now);
        post.setPublishTime(now);
        contentPostDao.insert(post);
        postContentKvPort.add(post.getContentUuid(), "relation job post content " + postId);
        return postId;
    }

    private void seedRelationUser(long userId) {
        UserBasePO userBase = new UserBasePO();
        userBase.setUserId(userId);
        userBase.setUsername("relation_job_" + userId);
        userBase.setNickname("relation-job-" + userId);
        userBase.setAvatarUrl("https://avatar.example/relation-job-" + userId + ".png");
        userBaseDao.insert(userBase);

        UserPrivacyPO privacy = userPrivacyDao.selectByUserId(userId);
        if (privacy == null) {
            userPrivacyDao.upsertNeedApproval(userId, false);
        }

        UserStatusPO status = new UserStatusPO();
        status.setUserId(userId);
        status.setStatus("ACTIVE");
        status.setDeactivatedTime(null);
        status.setUpdateTime(new Date());
        userStatusDao.upsert(status);
    }
}
