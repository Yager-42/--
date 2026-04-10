package cn.nexus.integration.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.infrastructure.dao.social.ICommentDao;
import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.trigger.mq.config.InteractionNotifyMqConfig;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

class ReactionHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Autowired
    private ICommentDao commentDao;

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @Test
    void postLike_shouldWriteRedisTruthEventLogAndCreateNotification() throws Exception {
        ensureNotifyConsumersReady();
        TestSession author = registerAndLoginSession("like-author");
        TestSession liker = registerAndLoginSession("liker");
        long postId = seedPublishedPost(author.userId());

        // 避免缓存穿透残留影响作者归属解析与计数对齐。
        deleteRedisKey("interact:content:author:" + postId);

        JsonNode like = postJson("/api/v1/interact/reaction", JsonNodeFactory.instance.objectNode()
                .put("requestId", "rid-" + uniqueUuid())
                .put("targetId", postId)
                .put("targetType", "POST")
                .put("type", "LIKE")
                .put("action", "ADD"), liker.token());
        JsonNode likeData = assertSuccess(like);
        assertThat(likeData.path("success").asBoolean()).isTrue();
        assertThat(likeData.path("currentCount").asLong()).isEqualTo(1L);
        String requestId = likeData.path("requestId").asText();
        String notifyEventId = "notify:like:" + requestId;

        JsonNode state = assertSuccess(getJson("/api/v1/interact/reaction/state?targetId=" + postId + "&targetType=POST&type=LIKE", liker.token()));
        assertThat(state.path("state").asBoolean()).isTrue();
        assertThat(state.path("currentCount").asLong()).isGreaterThanOrEqualTo(1L);

        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(readRedisLong("interact:reaction:cnt:{POST:" + postId + ":LIKE}")).isEqualTo(1L);
            assertThat(queryReactionEventLogCount("POST", postId, "LIKE")).isEqualTo(1L);
        });

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            String inbox = notifyInboxStatus(notifyEventId);
            assertThat(inbox).isEqualTo("DONE");
        });

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(notificationUnreadCount(author.userId(), "POST_LIKED", "POST", postId)).isGreaterThan(0L));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingReliableMqMessages();
            JsonNode list = assertSuccess(getJson("/api/v1/notification/list", author.token()));
            assertThat(list.path("notifications")).isNotEmpty();
            JsonNode first = list.path("notifications").get(0);
            assertThat(first.path("bizType").asText()).isEqualTo("POST_LIKED");
            assertThat(first.path("targetType").asText()).isEqualTo("POST");
            assertThat(first.path("targetId").asLong()).isEqualTo(postId);
            assertThat(first.path("unreadCount").asLong()).isGreaterThanOrEqualTo(1L);

            long notificationId = first.path("notificationId").asLong();
            assertSuccess(postJson("/api/v1/notification/read", JsonNodeFactory.instance.objectNode()
                    .put("notificationId", notificationId), author.token()));

            assertSuccess(postJson("/api/v1/notification/read/all", JsonNodeFactory.instance.objectNode(), author.token()));
        });
    }

    @Test
    void commentLike_shouldUpdateHotRankRejectLikersAndCreateNotification() throws Exception {
        ensureNotifyConsumersReady();
        ensureCommentConsumersReady();
        TestSession author = registerAndLoginSession("commentlike-author");
        TestSession commenter = registerAndLoginSession("commenter");
        TestSession liker = registerAndLoginSession("comment-liker");
        long postId = seedPublishedPost(author.userId());

        long rootCommentId = uniqueId();
        createRootComment(postId, rootCommentId, commenter.token());
        publishPendingReliableMqMessages();
        commentHotRankRepository.clear(postId);

        JsonNode like = postJson("/api/v1/interact/reaction", JsonNodeFactory.instance.objectNode()
                .put("requestId", "rid-" + uniqueUuid())
                .put("targetId", rootCommentId)
                .put("targetType", "COMMENT")
                .put("type", "LIKE")
                .put("action", "ADD"), liker.token());
        assertSuccess(like);

        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(readRedisLong("interact:reaction:cnt:{COMMENT:" + rootCommentId + ":LIKE}")).isEqualTo(1L);
            assertThat(queryReactionEventLogCount("COMMENT", rootCommentId, "LIKE")).isEqualTo(1L);
            assertThat(commentHotRankRepository.topIds(postId, 10)).contains(rootCommentId);
            assertThat(commentDao.selectBriefById(rootCommentId).getLikeCount()).isEqualTo(1L);
        });

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingReliableMqMessages();
            JsonNode list = assertSuccess(getJson("/api/v1/notification/list", commenter.token()));
            assertThat(list.path("notifications")).isNotEmpty();
            JsonNode first = list.path("notifications").get(0);
            assertThat(first.path("bizType").asText()).isEqualTo("COMMENT_LIKED");
            assertThat(first.path("targetType").asText()).isEqualTo("COMMENT");
            assertThat(first.path("targetId").asLong()).isEqualTo(rootCommentId);
            assertThat(first.path("unreadCount").asLong()).isGreaterThanOrEqualTo(1L);
        });
    }

    @Test
    void commentCreateReplyAndMention_shouldCreateDifferentNotificationBizTypes() throws Exception {
        ensureNotifyConsumersReady();
        TestSession author = registerAndLoginSession("notify-post-author");
        TestSession rootCommentAuthor = registerAndLoginSession("notify-root-author");
        TestSession mentionedUser = registerAndLoginSession("notify-mentioned");
        TestSession actor = registerAndLoginSession("notify-actor");
        long postId = seedPublishedPost(author.userId());

        String mentionedUsername = userBaseDao.selectByUserId(mentionedUser.userId()).getUsername();
        assertThat(mentionedUsername).isNotBlank();

        long rootCommentId = uniqueId();
        JsonNode rootComment = assertSuccess(postJson("/api/v1/interact/comment", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("content", "notify-root-" + uniqueUuid())
                .put("commentId", rootCommentId), rootCommentAuthor.token()));
        assertThat(rootComment.path("commentId").asLong()).isEqualTo(rootCommentId);

        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingReliableMqMessages();
            assertThat(notificationUnreadCount(author.userId(), "POST_COMMENTED", "POST", postId)).isGreaterThanOrEqualTo(1L);
            JsonNode list = assertSuccess(getJson("/api/v1/notification/list", author.token()));
            assertThat(list.path("notifications"))
                    .extracting(node -> node.path("bizType").asText())
                    .contains("POST_COMMENTED");
            assertThat(list.path("notifications"))
                    .extracting(JsonNode::toString)
                    .anySatisfy(raw -> {
                        assertThat(raw).contains("\"bizType\":\"POST_COMMENTED\"");
                        assertThat(raw).contains("\"targetType\":\"POST\"");
                        assertThat(raw).contains("\"targetId\":" + postId);
                    });
        });

        long replyCommentId = uniqueId();
        JsonNode reply = assertSuccess(postJson("/api/v1/interact/comment", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("parentId", rootCommentId)
                .put("content", "reply mention @" + mentionedUsername + " " + uniqueUuid())
                .put("commentId", replyCommentId), actor.token()));
        assertThat(reply.path("commentId").asLong()).isEqualTo(replyCommentId);

        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingReliableMqMessages();
            assertThat(notificationUnreadCount(rootCommentAuthor.userId(), "COMMENT_REPLIED", "COMMENT", rootCommentId))
                    .isGreaterThanOrEqualTo(1L);
            JsonNode list = assertSuccess(getJson("/api/v1/notification/list", rootCommentAuthor.token()));
            assertThat(list.path("notifications"))
                    .extracting(node -> node.path("bizType").asText())
                    .contains("COMMENT_REPLIED");
            assertThat(list.path("notifications"))
                    .extracting(JsonNode::toString)
                    .anySatisfy(raw -> {
                        assertThat(raw).contains("\"bizType\":\"COMMENT_REPLIED\"");
                        assertThat(raw).contains("\"targetType\":\"COMMENT\"");
                        assertThat(raw).contains("\"targetId\":" + rootCommentId);
                    });
        });

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingReliableMqMessages();
            assertThat(notificationUnreadCount(mentionedUser.userId(), "COMMENT_MENTIONED", "COMMENT", rootCommentId))
                    .isGreaterThanOrEqualTo(1L);
            JsonNode list = assertSuccess(getJson("/api/v1/notification/list", mentionedUser.token()));
            assertThat(list.path("notifications"))
                    .extracting(node -> node.path("bizType").asText())
                    .contains("COMMENT_MENTIONED");
            assertThat(list.path("notifications"))
                    .extracting(JsonNode::toString)
                    .anySatisfy(raw -> {
                        assertThat(raw).contains("\"bizType\":\"COMMENT_MENTIONED\"");
                        assertThat(raw).contains("\"targetType\":\"COMMENT\"");
                        assertThat(raw).contains("\"targetId\":" + rootCommentId);
                    });
        });
    }

    @Test
    void reactionState_highConcurrencySmoke_shouldStayResponsive() throws Exception {
        TestSession author = registerAndLoginSession("state-load-author");
        TestSession liker = registerAndLoginSession("state-load-liker");
        long postId = seedPublishedPost(author.userId());
        deleteRedisKey("interact:content:author:" + postId);

        JsonNode like = assertSuccess(postJson("/api/v1/interact/reaction", JsonNodeFactory.instance.objectNode()
                .put("requestId", "rid-" + uniqueUuid())
                .put("targetId", postId)
                .put("targetType", "POST")
                .put("type", "LIKE")
                .put("action", "ADD"), liker.token()));
        assertThat(like.path("currentCount").asLong()).isEqualTo(1L);

        ConcurrentRunResult result = runConcurrentRequests(150, 30, 60, () -> {
            JsonNode state = assertSuccess(getJson("/api/v1/interact/reaction/state?targetId=" + postId + "&targetType=POST&type=LIKE", liker.token()));
            assertThat(state.path("state").asBoolean()).isTrue();
            assertThat(state.path("currentCount").asLong()).isGreaterThanOrEqualTo(1L);
        });

        printLoadSmoke("reaction-state", result);
        assertThat(result.failure()).isEqualTo(0);
        assertThat(result.success()).isEqualTo(result.totalRequests());
    }

    @Test
    void placeholderWalletAndPollApis_shouldRunThrough() throws Exception {
        TestSession user = registerAndLoginSession("placeholder-user");
        TestSession receiver = registerAndLoginSession("placeholder-receiver");

        JsonNode tip = assertSuccess(postJson("/api/v1/wallet/tip", JsonNodeFactory.instance.objectNode()
                .put("toUserId", receiver.userId())
                .put("amount", "8.88")
                .put("currency", "CNY")
                .put("postId", uniqueId()), user.token()));
        assertThat(tip.path("txId").asText()).isNotBlank();
        assertThat(tip.path("effectUrl").asText()).isNotBlank();

        JsonNodeFactory factory = JsonNodeFactory.instance;
        com.fasterxml.jackson.databind.node.ObjectNode pollReq = factory.objectNode();
        pollReq.put("question", "你喜欢这个接口吗?");
        pollReq.put("allowMulti", false);
        pollReq.put("expireSeconds", 3600);
        pollReq.putArray("options").add("喜欢").add("非常喜欢");

        JsonNode createdPoll = assertSuccess(postJson("/api/v1/interaction/poll/create", pollReq, user.token()));
        long pollId = createdPoll.path("pollId").asLong();
        assertThat(pollId).isPositive();

        com.fasterxml.jackson.databind.node.ObjectNode voteReq = factory.objectNode();
        voteReq.put("pollId", pollId);
        voteReq.putArray("optionIds").add(1L);

        JsonNode voted = assertSuccess(postJson("/api/v1/interaction/poll/vote", voteReq, user.token()));
        assertThat(voted.path("updatedStats").asText()).isEqualTo("VOTED");

        JsonNode balance = assertSuccess(getJson("/api/v1/wallet/balance?currencyType=CNY", user.token()));
        assertThat(balance.path("currencyType").asText()).isEqualTo("CNY");
        assertThat(balance.path("amount").asText()).isNotBlank();
    }

    @Test
    void postLike_highConcurrencySmoke_shouldRemainConsistent() throws Exception {
        ensureNotifyConsumersReady();
        TestSession author = registerAndLoginSession("load-author");
        TestSession liker = registerAndLoginSession("load-liker");
        long postId = seedPublishedPost(author.userId());
        deleteRedisKey("interact:content:author:" + postId);

        ConcurrentRunResult result = runConcurrentRequests(120, 24, 60, () -> {
            JsonNode resp = postJson("/api/v1/interact/reaction", JsonNodeFactory.instance.objectNode()
                    .put("requestId", "rid-" + uniqueUuid())
                    .put("targetId", postId)
                    .put("targetType", "POST")
                    .put("type", "LIKE")
                    .put("action", "ADD"), liker.token());
            JsonNode data = assertSuccess(resp);
            assertThat(data.path("currentCount").asLong()).isEqualTo(1L);
        });
        printLoadSmoke("reaction-post-like", result);

        publishPendingReliableMqMessages();
        ReactionTargetVO postLikeTarget = ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingReliableMqMessages();
            assertThat(readRedisLong("interact:reaction:cnt:{POST:" + postId + ":LIKE}")).isEqualTo(1L);
            assertThat(queryReactionEventLogCount("POST", postId, "LIKE")).isEqualTo(1L);
            assertThat(notificationUnreadCount(author.userId(), "POST_LIKED", "POST", postId)).isGreaterThanOrEqualTo(1L);
        });

        assertThat(result.failure()).isEqualTo(0);
        assertThat(result.success()).isEqualTo(result.totalRequests());
        assertThat(readRedisLong("interact:reaction:cnt:{POST:" + postId + ":LIKE}")).isEqualTo(1L);
    }

    private long seedPublishedPost(long authorId) {
        long postId = uniqueId();
        ContentPostPO post = new ContentPostPO();
        post.setPostId(postId);
        post.setUserId(authorId);
        post.setTitle("like-it-post-" + postId);
        post.setContentUuid(uniqueUuid());
        post.setSummary("reaction integration");
        post.setSummaryStatus(1);
        post.setMediaType(0);
        post.setStatus(ContentPostStatusEnumVO.PUBLISHED.getCode());
        post.setVisibility(ContentPostVisibilityEnumVO.PUBLIC.getCode());
        post.setVersionNum(1);
        post.setIsEdited(0);
        post.setPublishTime(new Date());
        contentPostDao.insert(post);
        return postId;
    }

    private void createRootComment(long postId, long rootCommentId, String token) throws Exception {
        JsonNode created = assertSuccess(postJson("/api/v1/interact/comment", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("content", "root-comment-" + uniqueUuid())
                .put("commentId", rootCommentId), token));
        assertThat(created.path("commentId").asLong()).isEqualTo(rootCommentId);
        assertThat(created.path("status").asText()).isEqualTo("OK");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(commentDao.selectBriefById(rootCommentId)).isNotNull());
    }

    private TestSession registerAndLoginSession(String nicknamePrefix) throws Exception {
        String phone = uniquePhone();
        String password = "Pwd@" + uniqueUuid().substring(0, 8);
        String nickname = nicknamePrefix + "-" + uniqueUuid().substring(0, 6);
        String token = registerAndLogin(phone, password, nickname);
        long userId = assertSuccess(getJson("/api/v1/auth/me", token)).path("userId").asLong();
        return new TestSession(userId, token);
    }

    private String reliableOutboxStatus(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM reliable_mq_outbox WHERE event_id = ?")) {
            ps.setString(1, eventId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query reliable_mq_outbox failed, eventId=" + eventId, e);
        }
    }

    private String notifyInboxStatus(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM interaction_notify_inbox WHERE event_id = ?")) {
            ps.setString(1, eventId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query interaction_notify_inbox failed, eventId=" + eventId, e);
        }
    }

    private long notificationUnreadCount(long toUserId, String bizType, String targetType, long targetId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(unread_count, 0) FROM interaction_notification " +
                             "WHERE to_user_id = ? AND biz_type = ? AND target_type = ? AND target_id = ?")) {
            ps.setLong(1, toUserId);
            ps.setString(2, bizType);
            ps.setString(3, targetType);
            ps.setLong(4, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("query interaction_notification failed, toUserId=" + toUserId, e);
        }
    }

    private void ensureNotifyConsumersReady() {
        startRabbitListenerContainers();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queueConsumerCount(InteractionNotifyMqConfig.Q_INTERACTION_NOTIFY)).isGreaterThan(0));
    }

    private void ensureCommentConsumersReady() {
        startRabbitListenerContainers();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(queueConsumerCount(InteractionCommentMqConfig.Q_COMMENT_LIKE_CHANGED)).isGreaterThan(0);
            assertThat(queueConsumerCount(InteractionCommentMqConfig.Q_REPLY_COUNT_CHANGED)).isGreaterThan(0);
        });
    }

    private void startRabbitListenerContainers() {
        rabbitListenerEndpointRegistry.getListenerContainers().forEach(container -> {
            try {
                if (!container.isRunning()) {
                    container.start();
                }
            } catch (Exception ignored) {
                // Let consumerCount assertion expose real readiness issue.
            }
        });
    }

    private long queueConsumerCount(String queueName) {
        return rabbitTemplate.execute(channel -> channel.consumerCount(queueName));
    }

    private record TestSession(long userId, String token) {
    }

}
