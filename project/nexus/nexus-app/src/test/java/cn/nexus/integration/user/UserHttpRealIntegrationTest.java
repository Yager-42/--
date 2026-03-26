package cn.nexus.integration.user;

import static org.assertj.core.api.Assertions.assertThat;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

class UserHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Test
    void updateMyProfile_shouldWriteMysqlAndRecordUserOutbox() throws Exception {
        String phone = uniquePhone();
        String password = "Pwd@" + uniqueUuid().substring(0, 8);
        String originalNickname = "before-" + uniqueUuid().substring(0, 4);
        String newNickname = "after-" + uniqueUuid().substring(0, 4);
        String token = registerAndLogin(phone, password, originalNickname);

        JsonNode meBefore = assertSuccess(getJson("/api/v1/auth/me", token));
        long userId = meBefore.path("userId").asLong();

        JsonNode update = postJson("/api/v1/user/me/profile", JsonNodeFactory.instance.objectNode()
                .put("nickname", newNickname)
                .put("avatarUrl", "https://avatar.example/" + uniqueUuid() + ".png"), token);
        assertSuccess(update);

        UserBasePO latest = userBaseDao.selectByUserId(userId);
        JsonNode meAfter = assertSuccess(getJson("/api/v1/user/me/profile", token));

        assertThat(latest.getNickname()).isEqualTo(newNickname);
        assertThat(meAfter.path("nickname").asText()).isEqualTo(newNickname);
        assertThat(userEventOutboxDao.selectByStatus("DONE", 50))
                .anySatisfy(po -> assertThat(po.getPayload()).contains("\"userId\":" + userId));
    }

    @Test
    void privacyAndProfileQueries_shouldReflectUpdatedState() throws Exception {
        String phone = uniquePhone();
        String password = "Pwd@" + uniqueUuid().substring(0, 8);
        String nickname = "viewer-" + uniqueUuid().substring(0, 4);
        String token = registerAndLogin(phone, password, nickname);
        long userId = assertSuccess(getJson("/api/v1/auth/me", token)).path("userId").asLong();

        JsonNode privacyBefore = assertSuccess(getJson("/api/v1/user/me/privacy", token));
        assertThat(privacyBefore.path("needApproval").asBoolean()).isFalse();

        JsonNode updatePrivacy = postJson("/api/v1/user/me/privacy", JsonNodeFactory.instance.objectNode().put("needApproval", true), token);
        assertSuccess(updatePrivacy);

        JsonNode privacyAfter = assertSuccess(getJson("/api/v1/user/me/privacy", token));
        JsonNode myProfile = assertSuccess(getJson("/api/v1/user/me/profile", token));
        JsonNode publicProfile = assertSuccess(getJson("/api/v1/user/profile?targetUserId=" + userId, token));
        JsonNode profilePage = assertSuccess(getJson("/api/v1/user/profile/page?targetUserId=" + userId, token));

        assertThat(privacyAfter.path("needApproval").asBoolean()).isTrue();
        assertThat(myProfile.path("userId").asLong()).isEqualTo(userId);
        assertThat(myProfile.path("nickname").asText()).isEqualTo(nickname);
        assertThat(publicProfile.path("userId").asLong()).isEqualTo(userId);
        assertThat(profilePage.path("profile").path("userId").asLong()).isEqualTo(userId);
        assertThat(profilePage.path("profile").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(profilePage.path("relation").path("followCount").asLong()).isZero();
        assertThat(profilePage.path("relation").path("followerCount").asLong()).isZero();
        assertThat(profilePage.path("risk").path("status").asText()).isEqualTo("NORMAL");
    }

    @Test
    void internalUpsert_shouldUpdateExistingUserAndDeactivateFurtherWrites() throws Exception {
        String phone = uniquePhone();
        String password = "Pwd@" + uniqueUuid().substring(0, 8);
        String token = registerAndLogin(phone, password, "origin-" + uniqueUuid().substring(0, 4));
        JsonNode me = assertSuccess(getJson("/api/v1/auth/me", token));
        long userId = me.path("userId").asLong();
        UserBasePO before = userBaseDao.selectByUserId(userId);

        JsonNode upsert = postJson("/api/v1/internal/user/upsert", JsonNodeFactory.instance.objectNode()
                .put("userId", userId)
                .put("username", before.getUsername())
                .put("nickname", "sync-" + uniqueUuid().substring(0, 4))
                .put("avatarUrl", "https://avatar.example/" + uniqueUuid() + ".png")
                .put("needApproval", true)
                .put("status", "DEACTIVATED"), token);
        assertSuccess(upsert);

        UserBasePO after = userBaseDao.selectByUserId(userId);
        assertThat(after.getNickname()).startsWith("sync-");
        assertThat(userPrivacyDao.selectByUserId(userId).getNeedApproval()).isTrue();
        assertThat(userStatusDao.selectByUserId(userId).getStatus()).isEqualTo("DEACTIVATED");

        JsonNode blockedWrite = postJson("/api/v1/user/me/privacy", JsonNodeFactory.instance.objectNode().put("needApproval", false), token);
        assertThat(blockedWrite.path("code").asText()).isEqualTo("0410");
    }
}
