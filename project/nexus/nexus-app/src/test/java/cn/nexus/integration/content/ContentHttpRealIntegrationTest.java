package cn.nexus.integration.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ContentHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Test
    void draftPublishEditHistoryRollbackAndDelete_shouldRunThroughRealChain() throws Exception {
        TestSession author = registerAndLoginSession("content-author");

        String contentV1 = "内容v1-" + uniqueUuid();
        String titleV1 = "标题v1-" + uniqueUuid().substring(0, 6);
        String contentV2 = "内容v2-" + uniqueUuid();
        String titleV2 = "标题v2-" + uniqueUuid().substring(0, 6);

        long postId = assertSuccess(putJson("/api/v1/content/draft", JsonNodeFactory.instance.objectNode()
                .put("title", "draft-" + uniqueUuid().substring(0, 6))
                .put("contentText", "draft-text-" + uniqueUuid()), author.token()))
                .path("draftId")
                .asLong();
        assertThat(postId).isPositive();

        JsonNode sync = assertSuccess(patchJson("/api/v1/content/draft/" + postId, JsonNodeFactory.instance.objectNode()
                .put("title", "draft-sync-" + uniqueUuid().substring(0, 6))
                .put("diffContent", "draft-sync-text-" + uniqueUuid())
                .put("clientVersion", 2L)
                .put("deviceId", "it-" + uniqueUuid().substring(0, 6)), author.token()));
        assertThat(sync.path("serverVersion").asLong()).isEqualTo(2L);

        deleteRedisKey("interact:content:post:" + postId);
        deleteDocumentQuietly(postId);

        JsonNode publish1 = assertSuccess(postJson("/api/v1/content/publish", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("title", titleV1)
                .put("text", contentV1)
                .put("visibility", "PUBLIC"), author.token()));
        assertThat(publish1.path("postId").asLong()).isEqualTo(postId);
        assertThat(publish1.path("attemptId").asLong()).isPositive();
        assertThat(publish1.path("versionNum").asLong()).isEqualTo(1L);
        assertThat(publish1.path("status").asText()).isEqualTo("PUBLISHED");

        long attemptId1 = publish1.path("attemptId").asLong();
        JsonNode attempt1 = assertSuccess(getJson("/api/v1/content/publish/attempt/" + attemptId1 + "?userId=" + author.userId(), author.token()));
        assertThat(attempt1.path("attemptId").asLong()).isEqualTo(attemptId1);
        assertThat(attempt1.path("postId").asLong()).isEqualTo(postId);
        assertThat(attempt1.path("userId").asLong()).isEqualTo(author.userId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode detail = assertSuccess(getJson("/api/v1/content/" + postId, author.token()));
            assertThat(detail.path("postId").asLong()).isEqualTo(postId);
            assertThat(detail.path("authorId").asLong()).isEqualTo(author.userId());
            assertThat(detail.path("title").asText()).isEqualTo(titleV1);
            assertThat(detail.path("content").asText()).isEqualTo(contentV1);
            assertThat(detail.path("status").asInt()).isEqualTo(ContentPostStatusEnumVO.PUBLISHED.getCode());
            assertThat(detail.path("visibility").asInt()).isEqualTo(ContentPostVisibilityEnumVO.PUBLIC.getCode());
            assertThat(detail.path("versionNum").asInt()).isEqualTo(1);
        });

        // 内容发布事件走 content_event_outbox，测试环境手动触发一次发布，避免依赖定时重试任务。
        publishPendingContentEvents();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("content_id").asLong()).isEqualTo(postId);
            assertThat(source.path("title").asText()).isEqualTo(titleV1);
            assertThat(source.path("body").asText()).isEqualTo(contentV1);
            assertThat(source.path("status").asText()).isEqualTo("published");
        });

        JsonNode publish2 = assertSuccess(postJson("/api/v1/content/publish", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("title", titleV2)
                .put("text", contentV2)
                .put("visibility", "PUBLIC"), author.token()));
        assertThat(publish2.path("postId").asLong()).isEqualTo(postId);
        assertThat(publish2.path("attemptId").asLong()).isPositive();
        assertThat(publish2.path("versionNum").asLong()).isEqualTo(2L);
        assertThat(publish2.path("status").asText()).isEqualTo("PUBLISHED");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode detail = assertSuccess(getJson("/api/v1/content/" + postId, author.token()));
            assertThat(detail.path("title").asText()).isEqualTo(titleV2);
            assertThat(detail.path("content").asText()).isEqualTo(contentV2);
            assertThat(detail.path("versionNum").asInt()).isEqualTo(2);
        });

        publishPendingContentEvents();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("title").asText()).isEqualTo(titleV2);
            assertThat(source.path("body").asText()).isEqualTo(contentV2);
            assertThat(source.path("status").asText()).isEqualTo("published");
        });

        JsonNode history = assertSuccess(getJson("/api/v1/content/" + postId + "/history?limit=10&offset=0", author.token()));
        JsonNode versions = history.path("versions");
        assertThat(versions.isArray()).isTrue();
        assertThat(versions.size()).isGreaterThanOrEqualTo(2);
        long version1Id = versions.get(0).path("versionId").asLong();
        assertThat(version1Id).isEqualTo(1L);

        JsonNode rollback = assertSuccess(postJson("/api/v1/content/" + postId + "/rollback", JsonNodeFactory.instance.objectNode()
                .put("targetVersionId", version1Id), author.token()));
        assertThat(rollback.path("success").asBoolean()).isTrue();
        assertThat(rollback.path("id").asLong()).isEqualTo(postId);
        assertThat(rollback.path("status").asText()).isEqualTo("ROLLED_BACK");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode detail = assertSuccess(getJson("/api/v1/content/" + postId, author.token()));
            assertThat(detail.path("content").asText()).isEqualTo(contentV1);
            assertThat(detail.path("versionNum").asInt()).isEqualTo(3);
        });

        publishPendingContentEvents();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("body").asText()).isEqualTo(contentV1);
            assertThat(source.path("status").asText()).isEqualTo("published");
        });

        JsonNode deleted = assertSuccess(deleteJson("/api/v1/content/" + postId, JsonNodeFactory.instance.objectNode(), author.token()));
        assertThat(deleted.path("success").asBoolean()).isTrue();
        assertThat(deleted.path("id").asLong()).isEqualTo(postId);
        assertThat(deleted.path("status").asText()).isEqualTo("DELETED");

        publishPendingContentEvents();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("content_id").asLong()).isEqualTo(postId);
            assertThat(source.path("status").asText()).isEqualTo("deleted");
        });
    }

    @Test
    void scheduleAndCancel_shouldControlPublishExecutionThroughDelayQueue() throws Exception {
        TestSession author = registerAndLoginSession("content-schedule");

        String title = "定时标题-" + uniqueUuid().substring(0, 6);
        String content = "定时正文-" + uniqueUuid();

        long postId = assertSuccess(putJson("/api/v1/content/draft", JsonNodeFactory.instance.objectNode()
                .put("title", title)
                .put("contentText", content), author.token()))
                .path("draftId")
                .asLong();
        assertThat(postId).isPositive();

        long publishTime = System.currentTimeMillis() + 800L;
        JsonNode scheduled = assertSuccess(postJson("/api/v1/content/schedule", JsonNodeFactory.instance.objectNode()
                .put("postId", postId)
                .put("publishTime", publishTime)
                .put("timezone", "Asia/Shanghai"), author.token()));
        long taskId = scheduled.path("taskId").asLong();
        assertThat(taskId).isPositive();

        // 定时消息走 ReliableMqOutboxService，测试里手动触发一次发布。
        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode audit = assertSuccess(getJson("/api/v1/content/schedule/" + taskId + "?userId=" + author.userId(), author.token()));
            assertThat(audit.path("taskId").asLong()).isEqualTo(taskId);
            assertThat(audit.path("userId").asLong()).isEqualTo(author.userId());
            assertThat(audit.path("status").asInt()).isEqualTo(2);
        });

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode detail = assertSuccess(getJson("/api/v1/content/" + postId, author.token()));
            assertThat(detail.path("title").asText()).isEqualTo(title);
            assertThat(detail.path("content").asText()).isEqualTo(content);
            assertThat(detail.path("status").asInt()).isEqualTo(ContentPostStatusEnumVO.PUBLISHED.getCode());
        });

        publishPendingContentEvents();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("content_id").asLong()).isEqualTo(postId);
            assertThat(source.path("title").asText()).isEqualTo(title);
            assertThat(source.path("body").asText()).isEqualTo(content);
            assertThat(source.path("status").asText()).isEqualTo("published");
        });

        long postId2 = assertSuccess(putJson("/api/v1/content/draft", JsonNodeFactory.instance.objectNode()
                .put("title", "cancel-" + uniqueUuid().substring(0, 6))
                .put("contentText", "cancel-text-" + uniqueUuid()), author.token()))
                .path("draftId")
                .asLong();
        assertThat(postId2).isPositive();

        long publishTime2 = System.currentTimeMillis() + 800L;
        JsonNode scheduled2 = assertSuccess(postJson("/api/v1/content/schedule", JsonNodeFactory.instance.objectNode()
                .put("postId", postId2)
                .put("publishTime", publishTime2)
                .put("timezone", "Asia/Shanghai"), author.token()));
        long taskId2 = scheduled2.path("taskId").asLong();
        assertThat(taskId2).isPositive();

        JsonNode updated = assertSuccess(patchJson("/api/v1/content/schedule", JsonNodeFactory.instance.objectNode()
                .put("taskId", taskId2)
                .put("publishTime", publishTime2 + 1000L)
                .put("contentData", "")
                .put("reason", "adjust"), author.token()));
        assertThat(updated.path("success").asBoolean()).isTrue();
        assertThat(updated.path("id").asLong()).isEqualTo(taskId2);
        assertThat(updated.path("status").asText()).isEqualTo("UPDATED");

        JsonNode canceled = assertSuccess(postJson("/api/v1/content/schedule/cancel", JsonNodeFactory.instance.objectNode()
                .put("taskId", taskId2)
                .put("reason", "nope"), author.token()));
        assertThat(canceled.path("success").asBoolean()).isTrue();
        assertThat(canceled.path("id").asLong()).isEqualTo(taskId2);
        assertThat(canceled.path("status").asText()).isEqualTo("CANCELED");

        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode audit = assertSuccess(getJson("/api/v1/content/schedule/" + taskId2 + "?userId=" + author.userId(), author.token()));
            assertThat(audit.path("status").asInt()).isEqualTo(3);
            assertThat(audit.path("isCanceled").asInt()).isEqualTo(1);
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
