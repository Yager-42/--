package cn.nexus.integration.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CommentHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Test
    void commentCreateReplyPinAndQueries_shouldRunThroughRealChain() throws Exception {
        TestSession author = registerAndLoginSession("post-author");
        TestSession commenter = registerAndLoginSession("commenter");
        long postId = seedPublishedPost(author.userId());

        JsonNode rootComment = assertSuccess(postJson("/api/v1/interact/comment", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("content", "根评论-" + uniqueUuid()), commenter.token()));
        long rootCommentId = rootComment.path("commentId").asLong();
        assertThat(rootCommentId).isPositive();
        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode hot = assertSuccess(getJson("/api/v1/comment/hot?postId=" + postId + "&limit=10&preloadReplyLimit=5", commenter.token()));
            assertThat(hot.path("items")).isNotEmpty();
            assertThat(hot.path("items").get(0).path("root").path("commentId").asLong()).isEqualTo(rootCommentId);
            assertThat(hot.path("items").get(0).path("root").path("content").asText()).startsWith("根评论-");
        });

        JsonNode replyComment = assertSuccess(postJson("/api/v1/interact/comment", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("parentId", rootCommentId)
                .put("content", "楼中回复-" + uniqueUuid()), author.token()));
        long replyCommentId = replyComment.path("commentId").asLong();
        assertThat(replyCommentId).isPositive();
        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode list = assertSuccess(getJson("/api/v1/comment/list?postId=" + postId + "&limit=10&preloadReplyLimit=5", commenter.token()));
            JsonNode firstItem = list.path("items").get(0);
            assertThat(firstItem.path("root").path("commentId").asLong()).isEqualTo(rootCommentId);
            assertThat(firstItem.path("root").has("replyCount")).isFalse();
            assertThat(firstItem.path("repliesPreview").get(0).path("commentId").asLong()).isEqualTo(replyCommentId);

            JsonNode replies = assertSuccess(getJson("/api/v1/comment/reply/list?rootId=" + rootCommentId + "&limit=10", commenter.token()));
            assertThat(replies.path("items").get(0).path("commentId").asLong()).isEqualTo(replyCommentId);
            assertThat(replies.path("items").get(0).path("content").asText()).startsWith("楼中回复-");
        });

        JsonNode pin = postJson("/api/v1/interact/comment/pin", JsonNodeFactory.instance.objectNode()
                .put("commentId", rootCommentId)
                .put("postId", postId), author.token());
        assertSuccess(pin);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode list = assertSuccess(getJson("/api/v1/comment/list?postId=" + postId + "&limit=10&preloadReplyLimit=5", commenter.token()));
            assertThat(list.path("pinned").path("root").path("commentId").asLong()).isEqualTo(rootCommentId);
        });
    }

    @Test
    void deleteComment_shouldRemoveRootAndRepliesFromReadSide() throws Exception {
        TestSession author = registerAndLoginSession("delete-author");
        TestSession commenter = registerAndLoginSession("delete-commenter");
        long postId = seedPublishedPost(author.userId());

        long rootCommentId = assertSuccess(postJson("/api/v1/interact/comment", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("content", "待删除根评论-" + uniqueUuid()), commenter.token())).path("commentId").asLong();
        publishPendingReliableMqMessages();
        long replyCommentId = assertSuccess(postJson("/api/v1/interact/comment", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("parentId", rootCommentId)
                .put("content", "待删除回复-" + uniqueUuid()), author.token())).path("commentId").asLong();
        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode replies = assertSuccess(getJson("/api/v1/comment/reply/list?rootId=" + rootCommentId + "&limit=10", commenter.token()));
            assertThat(replies.path("items")).hasSize(1);
            assertThat(replies.path("items").get(0).path("commentId").asLong()).isEqualTo(replyCommentId);
        });

        JsonNode delete = deleteJson("/api/v1/comment/" + rootCommentId, null, author.token());
        assertSuccess(delete);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode list = assertSuccess(getJson("/api/v1/comment/list?postId=" + postId + "&limit=10&preloadReplyLimit=5", commenter.token()));
            assertThat(list.path("items")).isEmpty();

            JsonNode hot = assertSuccess(getJson("/api/v1/comment/hot?postId=" + postId + "&limit=10&preloadReplyLimit=5", commenter.token()));
            assertThat(hot.path("items")).isEmpty();

            JsonNode replies = assertSuccess(getJson("/api/v1/comment/reply/list?rootId=" + rootCommentId + "&limit=10", commenter.token()));
            assertThat(replies.path("items")).isEmpty();
        });
    }

    @Test
    void commentCreate_highConcurrencySmoke_shouldKeepWriteConsistency() throws Exception {
        TestSession author = registerAndLoginSession("comment-load-author");
        long postId = seedPublishedPost(author.userId());
        String prefix = "load-root-" + uniqueUuid().substring(0, 6) + "-";
        int beforeRootCount = countRootComments(postId);
        List<TestSession> commenters = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            commenters.add(registerAndLoginSession("comment-load-user-" + i));
        }
        AtomicInteger rr = new AtomicInteger(0);

        ConcurrentRunResult result = runConcurrentRequests(80, 16, 60, () -> {
            TestSession commenter = commenters.get(Math.floorMod(rr.getAndIncrement(), commenters.size()));
            JsonNode created = assertSuccess(postJson("/api/v1/interact/comment", JsonNodeFactory.instance.objectNode()
                    .put("postId", postId)
                    .put("content", prefix + uniqueUuid().substring(0, 8)), commenter.token()));
            assertThat(created.path("commentId").asLong()).isPositive();
        });

        printLoadSmoke("comment-create", result);
        assertThat(result.failure()).isEqualTo(0);
        assertThat(result.success()).isEqualTo(result.totalRequests());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(countRootComments(postId)).isEqualTo(beforeRootCount + result.totalRequests()));

        publishPendingReliableMqMessages();
        JsonNode hot = assertSuccess(getJson("/api/v1/comment/hot?postId=" + postId + "&limit=10&preloadReplyLimit=0", commenters.get(0).token()));
        assertThat(hot.path("items")).isNotEmpty();
    }

    private int countRootComments(long postId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(1) FROM interaction_comment WHERE post_id = ? AND root_id IS NULL")) {
            ps.setLong(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("count interaction_comment failed, postId=" + postId, e);
        }
    }

    private long seedPublishedPost(long authorId) {
        long postId = uniqueId();
        ContentPostPO post = new ContentPostPO();
        post.setPostId(postId);
        post.setUserId(authorId);
        post.setTitle("comment-it-post-" + postId);
        post.setContentUuid(uniqueUuid());
        post.setSummary("comment integration");
        post.setSummaryStatus(1);
        post.setMediaType(0);
        post.setStatus(ContentPostStatusEnumVO.PUBLISHED.getCode());
        post.setVisibility(ContentPostVisibilityEnumVO.PUBLIC.getCode());
        post.setVersionNum(1);
        post.setIsEdited(0);
        post.setPublishTime(new Date());
        contentPostDao.insert(post);
        postContentKvPort.add(post.getContentUuid(), "评论测试正文-" + postId);
        return postId;
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
