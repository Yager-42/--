package cn.nexus.integration.community;

import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

class CommunityHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Test
    void groupApis_shouldBeRemoved() throws Exception {
        TestSession user = registerAndLoginSession("group-user");

        assertPostNotFound("/api/v1/group/join", JsonNodeFactory.instance.objectNode()
                .put("groupId", uniqueId())
                .put("answers", "a=b")
                .put("inviteToken", ""), user.token());

        assertPostNotFound("/api/v1/group/join", JsonNodeFactory.instance.objectNode()
                .put("groupId", uniqueId())
                .put("answers", "a=b")
                .put("inviteToken", "token-" + uniqueUuid().substring(0, 6)), user.token());

        assertPostNotFound("/api/v1/group/member/kick", JsonNodeFactory.instance.objectNode()
                .put("groupId", uniqueId())
                .put("targetId", uniqueId())
                .put("reason", "spam")
                .put("ban", true), user.token());

        assertPostNotFound("/api/v1/group/member/role", JsonNodeFactory.instance.objectNode()
                .put("groupId", uniqueId())
                .put("targetId", uniqueId())
                .put("roleId", 2L), user.token());

        assertPostNotFound("/api/v1/group/channel/config", JsonNodeFactory.instance.objectNode()
                .put("channelId", uniqueId())
                .put("slowModeInterval", 5)
                .put("locked", false), user.token());
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
