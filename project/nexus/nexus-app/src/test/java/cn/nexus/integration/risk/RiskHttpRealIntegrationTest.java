package cn.nexus.integration.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
        assertThat(decision.path("result").asText()).isEqualTo("REVIEW");

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
                assertThat(item.path("result").asText()).isEqualTo("REVIEW");
                assertThat(item.path("reasonCode").asText()).isEqualTo("WSL_LLM_DISABLED");
            }
            assertThat(matched).isTrue();
        });
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
