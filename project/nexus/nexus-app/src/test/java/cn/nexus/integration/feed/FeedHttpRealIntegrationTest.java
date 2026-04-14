package cn.nexus.integration.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Test
    void timelineAndProfile_shouldAssembleCardsFromRedisInboxAndMysqlPosts() throws Exception {
        TestSession author = registerAndLoginSession("feed-author");
        TestSession viewer = registerAndLoginSession("feed-viewer");

        long nowMs = System.currentTimeMillis();
        long postId = seedPublishedPost(author.userId(), nowMs);

        assertSuccess(postJson("/api/v1/relation/follow", JsonNodeFactory.instance.objectNode()
                .put("targetId", author.userId()), viewer.token()));

        feedTimelineRepository.replaceInbox(viewer.userId(), List.of(FeedInboxEntryVO.builder()
                .postId(postId)
                .publishTimeMs(nowMs)
                .build()));

        JsonNode timeline = assertSuccess(getJson("/api/v1/feed/timeline?limit=10", viewer.token()));
        assertThat(timeline.path("items")).isNotEmpty();
        JsonNode first = timeline.path("items").get(0);
        assertThat(first.path("postId").asLong()).isEqualTo(postId);
        assertThat(first.path("authorId").asLong()).isEqualTo(author.userId());
        assertThat(first.path("authorNickname").asText()).isEqualTo(author.nickname());
        assertThat(first.path("followed").asBoolean()).isTrue();
        assertThat(timeline.path("nextCursor").isNull()).isTrue();
        assertThat(timeline.path("nextCursorTs").asLong()).isEqualTo(nowMs);
        assertThat(timeline.path("nextCursorPostId").asLong()).isEqualTo(postId);
        assertThat(timeline.path("hasMore").asBoolean()).isFalse();

        JsonNode profile = assertSuccess(getJson("/api/v1/feed/profile/" + author.userId() + "?limit=10", viewer.token()));
        assertThat(profile.path("items"))
                .extracting(JsonNode::toString)
                .anySatisfy(raw -> assertThat(raw).contains("\"postId\":" + postId));
    }

    @Test
    void timeline_highConcurrencySmoke_shouldRemainAvailable() throws Exception {
        TestSession author = registerAndLoginSession("feed-load-author");
        TestSession viewer = registerAndLoginSession("feed-load-viewer");

        long nowMs = System.currentTimeMillis();
        long postId = seedPublishedPost(author.userId(), nowMs);

        assertSuccess(postJson("/api/v1/relation/follow", JsonNodeFactory.instance.objectNode()
                .put("targetId", author.userId()), viewer.token()));

        feedTimelineRepository.replaceInbox(viewer.userId(), List.of(FeedInboxEntryVO.builder()
                .postId(postId)
                .publishTimeMs(nowMs)
                .build()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode timeline = assertSuccess(getJson("/api/v1/feed/timeline?limit=10", viewer.token()));
            assertThat(timeline.path("items")).isNotEmpty();
            assertThat(timeline.path("items").get(0).path("postId").asLong()).isEqualTo(postId);
        });

        ConcurrentRunResult result = runConcurrentRequests(120, 24, 60, () -> {
            JsonNode timeline = assertSuccess(getJson("/api/v1/feed/timeline?limit=10", viewer.token()));
            assertThat(timeline).isNotNull();
        });

        printLoadSmoke("feed-timeline", result);
        assertThat(result.failure()).isEqualTo(0);
        assertThat(result.success()).isEqualTo(result.totalRequests());

        deleteRedisKey("feed:follow:seen:" + viewer.userId());
        JsonNode timeline = assertSuccess(getJson("/api/v1/feed/timeline?limit=10", viewer.token()));
        assertThat(timeline.path("items"))
                .extracting(JsonNode::toString)
                .anySatisfy(raw -> assertThat(raw).contains("\"postId\":" + postId));
        assertThat(timeline.path("nextCursorTs").asLong()).isEqualTo(nowMs);
        assertThat(timeline.path("nextCursorPostId").asLong()).isEqualTo(postId);
    }

    @Test
    void recommendPopularAndNeighbors_shouldRunThroughWithExpectedResponseShape() throws Exception {
        TestSession author = registerAndLoginSession("feed-recommend-author");
        TestSession viewer = registerAndLoginSession("feed-recommend-viewer");
        long seedPostId = createAndPublishPost(author,
                "feed-seed-title-" + uniqueUuid().substring(0, 6),
                "feed seed body-" + uniqueUuid());
        long siblingPostId = createAndPublishPost(author,
                "feed-sibling-title-" + uniqueUuid().substring(0, 6),
                "feed sibling body-" + uniqueUuid());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingContentEvents();
            assertThat(reliableConsumerStatusByPayload("FeedRecommendItemUpsertConsumer", "\"postId\":" + seedPostId)).isEqualTo("DONE");
            assertThat(reliableConsumerStatusByPayload("FeedRecommendItemUpsertConsumer", "\"postId\":" + siblingPostId)).isEqualTo("DONE");
            assertThat(gorseItemOrNull(seedPostId)).isNotNull();
            assertThat(gorseItemOrNull(siblingPostId)).isNotNull();
        });

        JsonNode recommend = assertSuccess(getJson("/api/v1/feed/timeline?feedType=RECOMMEND&limit=10", viewer.token()));
        assertThat(recommend.path("items").isArray()).isTrue();

        JsonNode popular = assertSuccess(getJson("/api/v1/feed/timeline?feedType=POPULAR&limit=1", viewer.token()));
        assertThat(popular.path("items").isArray()).isTrue();
        if (!popular.path("items").isEmpty()) {
            assertThat(popular.path("nextCursor").asText()).startsWith("POP:");
            String popularCursor = popular.path("nextCursor").asText();
            JsonNode popularNext = assertSuccess(getJson("/api/v1/feed/timeline?feedType=POPULAR&cursor=" + popularCursor + "&limit=1", viewer.token()));
            assertThat(popularNext.path("items").isArray()).isTrue();
            if (!popularNext.path("items").isEmpty()) {
                assertThat(popularNext.path("nextCursor").asText()).startsWith("POP:");
            }
        }

        String neighborsCursor = "NEI:" + seedPostId + ":0";
        JsonNode neighbors = assertSuccess(getJson("/api/v1/feed/timeline?feedType=NEIGHBORS&cursor=" + neighborsCursor + "&limit=1", viewer.token()));
        assertThat(neighbors.path("items").isArray()).isTrue();
        if (!neighbors.path("items").isEmpty()) {
            JsonNode first = neighbors.path("items").get(0);
            assertThat(first.path("postId").asLong()).isNotEqualTo(seedPostId);
            assertThat(neighbors.path("nextCursor").asText()).startsWith("NEI:" + seedPostId + ":");
            String nextCursor = neighbors.path("nextCursor").asText();
            JsonNode neighborsNext = assertSuccess(getJson("/api/v1/feed/timeline?feedType=NEIGHBORS&cursor=" + nextCursor + "&limit=1", viewer.token()));
            assertThat(neighborsNext.path("items").isArray()).isTrue();
            if (!neighborsNext.path("items").isEmpty()) {
                assertThat(neighborsNext.path("items").get(0).path("postId").asLong()).isNotEqualTo(seedPostId);
            }
        }
    }

    @Test
    void recommendTimeline_shouldWriteReadFeedbackToGorseForReturnedItems() throws Exception {
        TestSession author = registerAndLoginSession("feed-read-author");
        TestSession viewer = registerAndLoginSession("feed-read-viewer");
        long postId = createAndPublishPost(author, "feed-read-title-" + uniqueUuid().substring(0, 6), "feed read body-" + uniqueUuid());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingContentEvents();
            assertThat(reliableConsumerStatusByPayload("FeedRecommendItemUpsertConsumer", "\"postId\":" + postId)).isEqualTo("DONE");
            assertThat(gorseItemOrNull(postId)).isNotNull();
        });

        JsonNode recommend = assertSuccess(getJson("/api/v1/feed/timeline?feedType=RECOMMEND&limit=10", viewer.token()));
        assertThat(recommend.path("items")).isNotEmpty();

        long returnedPostId = recommend.path("items").get(0).path("postId").asLong();
        assertThat(returnedPostId).isPositive();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode feedback = gorseGetJsonOrNull("/api/feedback/read/" + viewer.userId() + "/" + returnedPostId);
            assertThat(feedback).isNotNull();
            assertThat(feedback.path("FeedbackType").asText()).isEqualTo("read");
            assertThat(feedback.path("UserId").asText()).isEqualTo(String.valueOf(viewer.userId()));
            assertThat(feedback.path("ItemId").asText()).isEqualTo(String.valueOf(returnedPostId));
        });
    }

    @Test
    void postPublishedAndDeleted_shouldSyncRecommendItemsToGorse() throws Exception {
        TestSession author = registerAndLoginSession("feed-sync-author");
        long postId = createAndPublishPost(author, "feed-sync-title-" + uniqueUuid().substring(0, 6), "feed sync body-" + uniqueUuid());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingContentEvents();
            assertThat(reliableConsumerStatusByPayload("FeedRecommendItemUpsertConsumer", "\"postId\":" + postId)).isEqualTo("DONE");
            JsonNode item = gorseItemOrNull(postId);
            assertThat(item).isNotNull();
            assertThat(item.path("ItemId").asText()).isEqualTo(String.valueOf(postId));
        });

        JsonNode deleted = assertSuccess(deleteJson("/api/v1/content/" + postId, JsonNodeFactory.instance.objectNode(), author.token()));
        assertThat(deleted.path("success").asBoolean()).isTrue();
        assertThat(deleted.path("status").asText()).isEqualTo("DELETED");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingContentEvents();
            assertThat(reliableConsumerStatusByPayload("FeedRecommendItemDeleteConsumer", "\"postId\":" + postId)).isEqualTo("DONE");
            assertThat(gorseItemOrNull(postId)).isNull();
        });
    }

    @Test
    void likeCommentAndUnlike_shouldWriteRecommendFeedbackThroughAAndCChannels() throws Exception {
        TestSession author = registerAndLoginSession("feed-feedback-author");
        TestSession actor = registerAndLoginSession("feed-feedback-actor");
        long postId = createAndPublishPost(author, "feed-feedback-title-" + uniqueUuid().substring(0, 6), "feed feedback body-" + uniqueUuid());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingContentEvents();
            assertThat(reliableConsumerStatusByPayload("FeedRecommendItemUpsertConsumer", "\"postId\":" + postId)).isEqualTo("DONE");
            assertThat(gorseItemOrNull(postId)).isNotNull();
        });

        String likeRequestId = "rid-like-" + uniqueUuid().substring(0, 8);
        JsonNode like = assertSuccess(postJson("/api/v1/interact/reaction", JsonNodeFactory.instance.objectNode()
                .put("requestId", likeRequestId)
                .put("targetId", postId)
                .put("targetType", "POST")
                .put("type", "LIKE")
                .put("action", "ADD"), actor.token()));
        assertThat(like.path("success").asBoolean()).isTrue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingReliableMqMessages();
            assertThat(reliableConsumerStatusByPayload("FeedRecommendFeedbackAConsumer", "\"requestId\":\"" + likeRequestId + "\"")).isEqualTo("DONE");
            String payload = reliableConsumerPayloadByPayload("FeedRecommendFeedbackAConsumer", "\"requestId\":\"" + likeRequestId + "\"");
            assertThat(payload).contains("\"postId\":" + postId);
            assertThat(payload).contains("\"fromUserId\":" + actor.userId());
            JsonNode feedback = gorseGetJsonOrNull("/api/feedback/like/" + actor.userId() + "/" + postId);
            assertThat(feedback).isNotNull();
            assertThat(feedback.path("FeedbackType").asText()).isEqualTo("like");
        });

        long commentId = uniqueId();
        JsonNode comment = assertSuccess(postJson("/api/v1/interact/comment", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("content", "feed-comment-" + uniqueUuid())
                .put("commentId", commentId), actor.token()));
        assertThat(comment.path("commentId").asLong()).isEqualTo(commentId);
        assertThat(comment.path("status").asText()).isEqualTo("OK");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingReliableMqMessages();
            assertThat(reliableConsumerStatusByPayload("FeedRecommendFeedbackAConsumer", "\"commentId\":" + commentId)).isEqualTo("DONE");
            String payload = reliableConsumerPayloadByPayload("FeedRecommendFeedbackAConsumer", "\"commentId\":" + commentId);
            assertThat(payload).contains("\"postId\":" + postId);
            assertThat(payload).contains("\"commentId\":" + commentId);
            JsonNode feedback = gorseGetJsonOrNull("/api/feedback/comment/" + actor.userId() + "/" + postId);
            assertThat(feedback).isNotNull();
            assertThat(feedback.path("FeedbackType").asText()).isEqualTo("comment");
        });

        String unlikeRequestId = "rid-unlike-" + uniqueUuid().substring(0, 8);
        JsonNode unlike = assertSuccess(postJson("/api/v1/interact/reaction", JsonNodeFactory.instance.objectNode()
                .put("requestId", unlikeRequestId)
                .put("targetId", postId)
                .put("targetType", "POST")
                .put("type", "LIKE")
                .put("action", "REMOVE"), actor.token()));
        assertThat(unlike.path("success").asBoolean()).isTrue();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            publishPendingReliableMqMessages();
            assertThat(reliableConsumerStatusByPayload("FeedRecommendFeedbackConsumer", "\"postId\":" + postId)).isEqualTo("DONE");
            String payload = reliableConsumerPayloadByPayload("FeedRecommendFeedbackConsumer", "\"postId\":" + postId);
            assertThat(payload).contains("\"postId\":" + postId);
            assertThat(payload).contains("\"fromUserId\":" + actor.userId());
            assertThat(payload).contains("\"feedbackType\":\"unlike\"");
            JsonNode feedback = gorseGetJsonOrNull("/api/feedback/unlike/" + actor.userId() + "/" + postId);
            assertThat(feedback).isNotNull();
            assertThat(feedback.path("FeedbackType").asText()).isEqualTo("unlike");
        });
    }

    private long seedPublishedPost(long authorId, long nowMs) {
        long postId = uniqueId();
        Date now = new Date(nowMs);
        ContentPostPO post = new ContentPostPO();
        post.setPostId(postId);
        post.setUserId(authorId);
        post.setTitle("feed-post-" + postId);
        post.setContentUuid(uniqueUuid());
        post.setSummary("feed integration");
        post.setSummaryStatus(1);
        post.setMediaType(0);
        post.setStatus(ContentPostStatusEnumVO.PUBLISHED.getCode());
        post.setVisibility(ContentPostVisibilityEnumVO.PUBLIC.getCode());
        post.setVersionNum(1);
        post.setIsEdited(0);
        post.setCreateTime(now);
        post.setPublishTime(now);
        contentPostDao.insert(post);
        postContentKvPort.add(post.getContentUuid(), "feed body-" + postId);
        return postId;
    }

    private long createAndPublishPost(TestSession author, String title, String body) throws Exception {
        long postId = assertSuccess(putJson("/api/v1/content/draft", JsonNodeFactory.instance.objectNode()
                .put("title", title)
                .put("contentText", body), author.token()))
                .path("draftId")
                .asLong();
        JsonNode publish = assertSuccess(postJson("/api/v1/content/publish", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("title", title)
                .put("text", body)
                .put("visibility", "PUBLIC"), author.token()));
        assertThat(publish.path("postId").asLong()).isEqualTo(postId);
        assertThat(publish.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(publish.path("versionNum").asLong()).isEqualTo(1L);
        return postId;
    }

    private TestSession registerAndLoginSession(String nicknamePrefix) throws Exception {
        String phone = uniquePhone();
        String password = "Pwd@" + uniqueUuid().substring(0, 8);
        String nickname = nicknamePrefix + "-" + uniqueUuid().substring(0, 6);
        String token = registerAndLogin(phone, password, nickname);
        long userId = assertSuccess(getJson("/api/v1/auth/me", token)).path("userId").asLong();
        return new TestSession(userId, token, nickname);
    }

    private record TestSession(long userId, String token, String nickname) {
    }
}
