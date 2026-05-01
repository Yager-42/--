package cn.nexus.integration.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import cn.nexus.trigger.counter.CounterAggregationConsumer;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PostActionHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Autowired
    private CounterAggregationConsumer counterAggregationConsumer;

    @Test
    void postLikeFavAndUndo_shouldUseStrictZhiguangCountersAndBitmapState() throws Exception {
        TestSession author = registerAndLoginSession("action-author");
        TestSession actor = registerAndLoginSession("action-actor");
        long postId = seedPublishedPost(author.userId());

        JsonNode like = assertSuccess(postAction("like", postId, actor.token()));
        assertThat(like.path("changed").asBoolean()).isTrue();
        assertThat(like.path("liked").asBoolean()).isTrue();
        awaitPostCounts(actor.token(), postId, 1L, 0L);
        awaitDetailState(actor.token(), postId, true, false, 1L, 0L);

        JsonNode duplicateLike = assertSuccess(postAction("like", postId, actor.token()));
        assertThat(duplicateLike.path("changed").asBoolean()).isFalse();
        assertThat(duplicateLike.path("liked").asBoolean()).isTrue();
        awaitPostCounts(actor.token(), postId, 1L, 0L);

        JsonNode unlike = assertSuccess(postAction("unlike", postId, actor.token()));
        assertThat(unlike.path("changed").asBoolean()).isTrue();
        assertThat(unlike.path("liked").asBoolean()).isFalse();
        awaitPostCounts(actor.token(), postId, 0L, 0L);
        awaitDetailState(actor.token(), postId, false, false, 0L, 0L);

        JsonNode fav = assertSuccess(postAction("fav", postId, actor.token()));
        assertThat(fav.path("changed").asBoolean()).isTrue();
        assertThat(fav.path("faved").asBoolean()).isTrue();
        awaitPostCounts(actor.token(), postId, 0L, 1L);
        awaitDetailState(actor.token(), postId, false, true, 0L, 1L);

        JsonNode duplicateFav = assertSuccess(postAction("fav", postId, actor.token()));
        assertThat(duplicateFav.path("changed").asBoolean()).isFalse();
        assertThat(duplicateFav.path("faved").asBoolean()).isTrue();
        awaitPostCounts(actor.token(), postId, 0L, 1L);

        JsonNode unfav = assertSuccess(postAction("unfav", postId, actor.token()));
        assertThat(unfav.path("changed").asBoolean()).isTrue();
        assertThat(unfav.path("faved").asBoolean()).isFalse();
        awaitPostCounts(actor.token(), postId, 0L, 0L);
        awaitDetailState(actor.token(), postId, false, false, 0L, 0L);
    }

    @Test
    void commentTargetAction_shouldReturnParameterErrorAndCreateNoPostCounterKeys() throws Exception {
        TestSession actor = registerAndLoginSession("action-comment-target");
        long targetId = uniqueId();
        long chunk = actor.userId() / 10_000L;

        JsonNode response = postJson("/api/v1/action/like", JsonNodeFactory.instance.objectNode()
                .put("targetType", "comment")
                .put("targetId", targetId)
                .put("requestId", "comment-target-" + uniqueUuid()), actor.token());

        assertThat(response.path("code").asText()).isEqualTo("0002");
        assertThat(stringRedisTemplate.hasKey("bm:like:post:" + targetId + ":" + chunk)).isFalse();
        assertThat(stringRedisTemplate.hasKey("bm:fav:post:" + targetId + ":" + chunk)).isFalse();
        assertThat(stringRedisTemplate.hasKey("agg:v1:post:" + targetId)).isFalse();
        assertThat(stringRedisTemplate.hasKey("cnt:v1:post:" + targetId)).isFalse();
    }

    @Test
    void receivedCounters_shouldIncrementIdempotentlyAndRebuildFromPostObjectCounters() throws Exception {
        TestSession author = registerAndLoginSession("received-author");
        TestSession actor = registerAndLoginSession("received-actor");
        long postId = seedPublishedPost(author.userId());

        assertThat(assertSuccess(postAction("like", postId, actor.token())).path("changed").asBoolean()).isTrue();
        assertThat(assertSuccess(postAction("like", postId, actor.token())).path("changed").asBoolean()).isFalse();
        assertThat(assertSuccess(postAction("fav", postId, actor.token())).path("changed").asBoolean()).isTrue();
        assertThat(assertSuccess(postAction("fav", postId, actor.token())).path("changed").asBoolean()).isFalse();

        awaitPostCounts(actor.token(), postId, 1L, 1L);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode counter = assertSuccess(getJson("/api/v1/relation/counter", author.token()));
            assertThat(counter.path("likesReceived").asLong()).isEqualTo(1L);
            assertThat(counter.path("favsReceived").asLong()).isEqualTo(1L);
        });

        writeMalformedUserSnapshot(author.userId());
        JsonNode rebuilt = assertSuccess(getJson("/api/v1/relation/counter", author.token()));
        assertThat(rebuilt.path("likesReceived").asLong()).isEqualTo(1L);
        assertThat(rebuilt.path("favsReceived").asLong()).isEqualTo(1L);
    }

    private JsonNode postAction(String action, long postId, String token) throws Exception {
        return postJson("/api/v1/action/" + action, JsonNodeFactory.instance.objectNode()
                .put("targetType", "post")
                .put("targetId", postId)
                .put("requestId", action + "-" + uniqueUuid()), token);
    }

    private void awaitPostCounts(String token, long postId, long expectedLike, long expectedFav) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            counterAggregationConsumer.flushActiveBuckets();
            JsonNode counter = assertSuccess(getJson("/api/v1/counter/post/" + postId + "?metrics=like,fav", token));
            assertThat(counter.path("postId").asLong()).isEqualTo(postId);
            assertThat(counter.path("counts").path("like").asLong()).isEqualTo(expectedLike);
            assertThat(counter.path("counts").path("fav").asLong()).isEqualTo(expectedFav);
        });
    }

    private void awaitDetailState(String token,
                                  long postId,
                                  boolean liked,
                                  boolean faved,
                                  long expectedLike,
                                  long expectedFav) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode detail = assertSuccess(getJson("/api/v1/content/" + postId, token));
            assertThat(detail.path("liked").asBoolean()).isEqualTo(liked);
            assertThat(detail.path("faved").asBoolean()).isEqualTo(faved);
            assertThat(detail.path("likeCount").asLong()).isEqualTo(expectedLike);
            assertThat(detail.path("favoriteCount").asLong()).isEqualTo(expectedFav);
        });
    }

    private long seedPublishedPost(long authorId) {
        long postId = uniqueId();
        Date now = new Date();
        ContentPostPO post = new ContentPostPO();
        post.setPostId(postId);
        post.setUserId(authorId);
        post.setTitle("action-post-" + postId);
        post.setContentUuid(uniqueUuid());
        post.setSummary("post action integration");
        post.setSummaryStatus(1);
        post.setMediaType(0);
        post.setStatus(ContentPostStatusEnumVO.PUBLISHED.getCode());
        post.setVisibility(ContentPostVisibilityEnumVO.PUBLIC.getCode());
        post.setVersionNum(1);
        post.setIsEdited(0);
        post.setCreateTime(now);
        post.setPublishTime(now);
        contentPostDao.insert(post);
        postContentKvPort.add(post.getContentUuid(), "action post body " + postId);
        return postId;
    }

    private void writeMalformedUserSnapshot(long userId) {
        stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) connection -> {
            connection.stringCommands().set(("ucnt:" + userId).getBytes(StandardCharsets.UTF_8), new byte[]{1, 2, 3});
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
}
