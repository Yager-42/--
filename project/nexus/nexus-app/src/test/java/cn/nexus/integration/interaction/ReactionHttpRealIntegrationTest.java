package cn.nexus.integration.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.infrastructure.dao.social.ICommentDao;
import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReactionHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Autowired
    private ICommentDao commentDao;

    @Test
    void postLike_shouldPersistEdgesAggregateCountsAndCreateNotification() throws Exception {
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

        JsonNode state = assertSuccess(getJson("/api/v1/interact/reaction/state?targetId=" + postId + "&targetType=POST&type=LIKE", liker.token()));
        assertThat(state.path("state").asBoolean()).isTrue();
        assertThat(state.path("currentCount").asLong()).isGreaterThanOrEqualTo(1L);

        publishPendingReliableMqMessages();

        ReactionTargetVO postLikeTarget = ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(reactionRepository.exists(postLikeTarget, liker.userId())).isTrue();
            assertThat(reactionRepository.getCount(postLikeTarget)).isEqualTo(1L);
        });

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
    void commentLike_shouldUpdateHotRankSupportLikersAndCreateNotification() throws Exception {
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

        ReactionTargetVO commentLikeTarget = ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.COMMENT)
                .targetId(rootCommentId)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(reactionRepository.exists(commentLikeTarget, liker.userId())).isTrue();
            assertThat(reactionRepository.getCount(commentLikeTarget)).isEqualTo(1L);
            assertThat(commentHotRankRepository.topIds(postId, 10)).contains(rootCommentId);
            assertThat(commentDao.selectBriefById(rootCommentId).getLikeCount()).isEqualTo(1L);
        });

        JsonNode likers = assertSuccess(getJson("/api/v1/interact/reaction/likers?targetId=" + rootCommentId + "&targetType=COMMENT&type=LIKE&limit=10", liker.token()));
        assertThat(likers.path("items"))
                .extracting(JsonNode::toString)
                .anySatisfy(raw -> assertThat(raw).contains("\"userId\":" + liker.userId()));

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

    private record TestSession(long userId, String token) {
    }
}
