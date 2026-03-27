package cn.nexus.integration.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RiskHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Test
    void decisionAndAsyncScan_shouldPersistDecisionAndUpdateDecisionLog() throws Exception {
        TestSession admin = registerAndLoginAdminSession();
        TestSession user = registerAndLoginSession("risk-user");

        String eventId = "evt-" + uniqueUuid();
        var req = JsonNodeFactory.instance.objectNode();
        req.put("eventId", eventId);
        req.put("actionType", "PUBLISH_POST");
        req.put("scenario", "post.publish");
        req.put("contentText", "");
        req.putArray("mediaUrls").add("https://img.example/" + uniqueUuid() + ".png");
        req.put("targetId", String.valueOf(uniqueId()));
        req.put("ext", "{\"attemptId\":1}");

        JsonNode decision = assertSuccess(postJson("/api/v1/risk/decision", req, user.token()));
        long decisionId = decision.path("decisionId").asLong();
        assertThat(decisionId).isPositive();
        assertThat(decision.path("result").asText()).isIn("PASS", "REVIEW", "BLOCK", "LIMIT");

        // 触发 ReliableMqOutbox 发布，推进到 MQ consumer 做异步回写。
        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode list = assertSuccess(getJson("/api/v1/risk/admin/decisions?userId=" + user.userId() + "&limit=10&offset=0", admin.token()));
            assertThat(list.path("decisions")).isNotEmpty();

            boolean matched = false;
            for (JsonNode item : list.path("decisions")) {
                if (item.path("decisionId").asLong() != decisionId) {
                    continue;
                }
                matched = true;
                assertThat(item.path("eventId").asText()).isEqualTo(eventId);
                assertThat(item.path("result").asText()).isIn("PASS", "REVIEW", "BLOCK", "LIMIT");
                assertThat(item.path("reasonCode").asText()).isNotBlank();
            }
            assertThat(matched).isTrue();
        });
    }

    @Test
    void textImageStatusAppealAndAdminGovernance_shouldRunThrough() throws Exception {
        TestSession admin = registerAndLoginAdminSession();
        TestSession user = registerAndLoginSession("risk-govern-user");

        JsonNode textScan = assertSuccess(postJson("/api/v1/risk/scan/text", JsonNodeFactory.instance.objectNode()
                .put("content", "normal text " + uniqueUuid())
                .put("scenario", "comment.create"), user.token()));
        assertThat(textScan.path("result").asText()).isNotBlank();
        assertThat(textScan.path("tags").isArray()).isTrue();

        JsonNode imageScan = assertSuccess(postJson("/api/v1/risk/scan/image", JsonNodeFactory.instance.objectNode()
                .put("imageUrl", "https://img.example/" + uniqueUuid() + ".png"), user.token()));
        assertThat(imageScan.path("taskId").asText()).startsWith("task-");

        JsonNode statusBefore = assertSuccess(getJson("/api/v1/risk/user/status", user.token()));
        assertThat(statusBefore.path("status").asText()).isNotBlank();
        assertThat(statusBefore.path("capabilities").isArray()).isTrue();

        String appealEventId = "evt-appeal-" + uniqueUuid();
        JsonNode decision = assertSuccess(postJson("/api/v1/risk/decision", JsonNodeFactory.instance.objectNode()
                .put("eventId", appealEventId)
                .put("actionType", "COMMENT_CREATE")
                .put("scenario", "comment.create")
                .put("contentText", "appeal-source-" + uniqueUuid())
                .put("targetId", String.valueOf(uniqueId()))
                .put("ext", "{\"source\":\"it\"}"), user.token()));
        long decisionId = decision.path("decisionId").asLong();
        assertThat(decisionId).isPositive();

        JsonNode appeal = assertSuccess(postJson("/api/v1/risk/appeals", JsonNodeFactory.instance.objectNode()
                .put("decisionId", decisionId)
                .put("content", "我认为这是误判-" + uniqueUuid().substring(0, 6)), user.token()));
        long appealId = appeal.path("appealId").asLong();
        assertThat(appealId).isPositive();
        assertThat(appeal.path("status").asText()).isEqualTo("OPEN");

        JsonNode appealDecision = assertSuccess(postJson("/api/v1/risk/admin/appeals/" + appealId + "/decision", JsonNodeFactory.instance.objectNode()
                .put("result", "REJECT"), admin.token()));
        assertThat(appealDecision.path("success").asBoolean()).isTrue();

        String rulesJson = "{\"version\":1,\"shadow\":false,\"rules\":[]}";
        JsonNode upsertRule = assertSuccess(postJson("/api/v1/risk/admin/rules/versions", JsonNodeFactory.instance.objectNode()
                .putNull("version")
                .put("rulesJson", rulesJson), admin.token()));
        long ruleVersion = upsertRule.path("version").asLong();
        assertThat(ruleVersion).isPositive();

        JsonNode publishRule = assertSuccess(postJson("/api/v1/risk/admin/rules/versions/" + ruleVersion + "/publish", JsonNodeFactory.instance.objectNode()
                .put("shadow", true)
                .put("canaryPercent", 100)
                .put("canarySalt", "it"), admin.token()));
        assertThat(publishRule.path("success").asBoolean()).isTrue();

        JsonNode listRules = assertSuccess(getJson("/api/v1/risk/admin/rules/versions?includeRulesJson=true", admin.token()));
        assertThat(listRules.path("versions"))
                .extracting(JsonNode::toString)
                .anySatisfy(raw -> assertThat(raw).contains("\"version\":" + ruleVersion));

        JsonNode upsertPrompt = assertSuccess(postJson("/api/v1/risk/admin/prompts/versions", JsonNodeFactory.instance.objectNode()
                .putNull("version")
                .put("contentType", "TEXT")
                .put("promptText", "prompt-" + uniqueUuid())
                .put("model", "mock-model"), admin.token()));
        long promptVersion = upsertPrompt.path("version").asLong();
        assertThat(promptVersion).isPositive();

        JsonNode publishPrompt = assertSuccess(postJson("/api/v1/risk/admin/prompts/versions/" + promptVersion + "/publish",
                JsonNodeFactory.instance.objectNode(), admin.token()));
        assertThat(publishPrompt.path("success").asBoolean()).isTrue();

        JsonNode listPrompts = assertSuccess(getJson("/api/v1/risk/admin/prompts/versions?contentType=TEXT&includePromptText=true", admin.token()));
        assertThat(listPrompts.path("versions"))
                .extracting(JsonNode::toString)
                .anySatisfy(raw -> assertThat(raw).contains("\"version\":" + promptVersion));

        JsonNode applyPunish = assertSuccess(postJson("/api/v1/risk/admin/punishments/apply", JsonNodeFactory.instance.objectNode()
                .put("userId", user.userId())
                .put("type", "COMMENT_BAN")
                .put("reasonCode", "IT_TEST")
                .put("durationSeconds", 120), admin.token()));
        long punishId = applyPunish.path("id").asLong();
        assertThat(applyPunish.path("success").asBoolean()).isTrue();
        assertThat(punishId).isPositive();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode status = assertSuccess(getJson("/api/v1/risk/user/status", user.token()));
            assertThat(status.path("capabilities")).extracting(JsonNode::asText).doesNotContain("COMMENT");
        });

        JsonNode listPunishments = assertSuccess(getJson("/api/v1/risk/admin/punishments?userId=" + user.userId() + "&limit=10&offset=0", admin.token()));
        assertThat(listPunishments.path("punishments"))
                .extracting(JsonNode::toString)
                .anySatisfy(raw -> assertThat(raw).contains("\"punishId\":" + punishId));

        JsonNode revokePunish = assertSuccess(postJson("/api/v1/risk/admin/punishments/revoke", JsonNodeFactory.instance.objectNode()
                .put("punishId", punishId), admin.token()));
        assertThat(revokePunish.path("success").asBoolean()).isTrue();
    }

    @Test
    void decision_highConcurrencySmoke_shouldKeepWriteAndAsyncStable() throws Exception {
        TestSession admin = registerAndLoginAdminSession();
        TestSession user = registerAndLoginSession("risk-load-user");
        String eventPrefix = "evt-load-" + uniqueUuid().substring(0, 6) + "-";

        ConcurrentRunResult result = runConcurrentRequests(60, 12, 60, () -> {
            var req = JsonNodeFactory.instance.objectNode();
            req.put("eventId", eventPrefix + uniqueUuid().substring(0, 8));
            req.put("actionType", "PUBLISH_POST");
            req.put("scenario", "post.publish");
            req.put("contentText", "");
            req.putArray("mediaUrls").add("https://img.example/" + uniqueUuid() + ".png");
            req.put("targetId", String.valueOf(uniqueId()));
            req.put("ext", "{\"load\":true}");
            JsonNode decision = assertSuccess(postJson("/api/v1/risk/decision", req, user.token()));
            assertThat(decision.path("decisionId").asLong()).isPositive();
        });

        printLoadSmoke("risk-decision", result);
        assertThat(result.failure()).isEqualTo(0);
        assertThat(result.success()).isEqualTo(result.totalRequests());

        publishPendingReliableMqMessages();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(countRiskDecisionByPrefix(user.userId(), eventPrefix)).isEqualTo(result.totalRequests()));

        JsonNode list = assertSuccess(getJson("/api/v1/risk/admin/decisions?userId=" + user.userId() + "&limit=10&offset=0", admin.token()));
        assertThat(list.path("decisions")).isNotEmpty();
    }

    private int countRiskDecisionByPrefix(long userId, String eventPrefix) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(1) FROM risk_decision_log WHERE user_id = ? AND event_id LIKE ?")) {
            ps.setLong(1, userId);
            ps.setString(2, eventPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("count risk_decision_log failed, userId=" + userId, e);
        }
    }

    private TestSession registerAndLoginAdminSession() throws Exception {
        String phone = uniquePhone();
        String password = "Adm@" + uniqueUuid().substring(0, 8);
        long userId = registerUser(phone, password, "admin-" + uniqueUuid().substring(0, 6));
        grantRole(userId, "ADMIN");
        String token = passwordLogin(phone, password);
        return new TestSession(userId, token);
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
