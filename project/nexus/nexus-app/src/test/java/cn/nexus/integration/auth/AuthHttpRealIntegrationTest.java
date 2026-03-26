package cn.nexus.integration.auth;

import static org.assertj.core.api.Assertions.assertThat;

import cn.nexus.infrastructure.dao.auth.po.AuthAccountPO;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import cn.nexus.infrastructure.dao.user.po.UserStatusPO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

class AuthHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Test
    void registerPasswordLoginAndMe_shouldPersistAccountAndReturnCurrentUser() throws Exception {
        String phone = uniquePhone();
        String password = "Pwd@" + uniqueUuid().substring(0, 8);
        String nickname = "auth-" + uniqueUuid().substring(0, 6);

        long userId = registerUser(phone, password, nickname);

        AuthAccountPO account = authAccountDao.selectByPhone(phone);
        UserBasePO userBase = userBaseDao.selectByUserId(userId);
        UserStatusPO status = userStatusDao.selectByUserId(userId);

        assertThat(account).isNotNull();
        assertThat(account.getUserId()).isEqualTo(userId);
        assertThat(userBase).isNotNull();
        assertThat(userBase.getNickname()).isEqualTo(nickname);
        assertThat(status).isNotNull();
        assertThat(status.getStatus()).isEqualTo("ACTIVE");
        assertThat(authUserRoleDao.countByUserIdAndRoleCode(userId, "USER")).isEqualTo(1);

        String token = passwordLogin(phone, password);
        JsonNode meData = assertSuccess(getJson("/api/v1/auth/me", token));

        assertThat(meData.path("userId").asLong()).isEqualTo(userId);
        assertThat(meData.path("phone").asText()).isEqualTo(phone);
        assertThat(meData.path("nickname").asText()).isEqualTo(nickname);
        assertThat(meData.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(authAccountDao.selectByUserId(userId).getLastLoginAt()).isNotNull();
    }

    @Test
    void changePassword_shouldRejectOldPasswordAndAcceptNewPassword() throws Exception {
        String phone = uniquePhone();
        String oldPassword = "Old@" + uniqueUuid().substring(0, 8);
        String newPassword = "New@" + uniqueUuid().substring(0, 8);

        String token = registerAndLogin(phone, oldPassword, "changer-" + uniqueUuid().substring(0, 6));

        JsonNode change = postJson("/api/v1/auth/password/change", JsonNodeFactory.instance.objectNode()
                .put("oldPassword", oldPassword)
                .put("newPassword", newPassword), token);
        assertSuccess(change);

        JsonNode oldLogin = postJson("/api/v1/auth/login/password", JsonNodeFactory.instance.objectNode()
                .put("phone", phone)
                .put("password", oldPassword), null);
        assertThat(oldLogin.path("code").asText()).isNotEqualTo("0000");

        JsonNode newLogin = postJson("/api/v1/auth/login/password", JsonNodeFactory.instance.objectNode()
                .put("phone", phone)
                .put("password", newPassword), null);
        assertThat(bearerToken(newLogin)).isNotBlank();
    }

    @Test
    void adminEndpoints_shouldGrantListAndRevokeRoles() throws Exception {
        String adminPhone = uniquePhone();
        String adminPassword = "Adm@" + uniqueUuid().substring(0, 8);
        long adminUserId = registerUser(adminPhone, adminPassword, "admin-" + uniqueUuid().substring(0, 4));
        grantRole(adminUserId, "ADMIN");
        String adminToken = passwordLogin(adminPhone, adminPassword);

        String targetPhone = uniquePhone();
        String targetPassword = "Usr@" + uniqueUuid().substring(0, 8);
        long targetUserId = registerUser(targetPhone, targetPassword, "target-" + uniqueUuid().substring(0, 4));

        JsonNode grant = postJson("/api/v1/auth/admin/grant", JsonNodeFactory.instance.objectNode().put("userId", targetUserId), adminToken);
        assertSuccess(grant);
        assertThat(authUserRoleDao.countByUserIdAndRoleCode(targetUserId, "ADMIN")).isEqualTo(1);

        JsonNode admins = getJson("/api/v1/auth/admins", adminToken);
        JsonNode userIds = assertSuccess(admins).path("userIds");
        assertThat(userIds).extracting(JsonNode::asLong).contains(adminUserId, targetUserId);

        JsonNode revoke = postJson("/api/v1/auth/admin/revoke", JsonNodeFactory.instance.objectNode().put("userId", targetUserId), adminToken);
        assertSuccess(revoke);
        assertThat(authUserRoleDao.countByUserIdAndRoleCode(targetUserId, "ADMIN")).isEqualTo(0);
    }
}
