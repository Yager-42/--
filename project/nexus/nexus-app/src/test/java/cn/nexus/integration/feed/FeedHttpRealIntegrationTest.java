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
    }

    @Test
    void recommendPopularAndNeighbors_shouldRunThroughWithExpectedResponseShape() throws Exception {
        TestSession author = registerAndLoginSession("feed-recommend-author");
        TestSession viewer = registerAndLoginSession("feed-recommend-viewer");
        long seedPostId = seedPublishedPost(author.userId(), System.currentTimeMillis());

        JsonNode recommend = assertSuccess(getJson("/api/v1/feed/timeline?feedType=RECOMMEND&limit=10", viewer.token()));
        assertThat(recommend.path("items").isArray()).isTrue();

        JsonNode popular = assertSuccess(getJson("/api/v1/feed/timeline?feedType=POPULAR&limit=10", viewer.token()));
        assertThat(popular.path("items").isArray()).isTrue();

        String neighborsCursor = "NEI:" + seedPostId + ":0";
        JsonNode neighbors = assertSuccess(getJson("/api/v1/feed/timeline?feedType=NEIGHBORS&cursor=" + neighborsCursor + "&limit=10", viewer.token()));
        assertThat(neighbors.path("items").isArray()).isTrue();
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
