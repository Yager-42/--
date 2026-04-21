package cn.nexus.trigger.http.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import cn.nexus.api.auth.dto.AuthAdminDTO;
import cn.nexus.api.auth.dto.AuthChangePasswordRequestDTO;
import cn.nexus.api.auth.dto.AuthAdminListResponseDTO;
import cn.nexus.api.auth.dto.AuthGrantAdminRequestDTO;
import cn.nexus.api.auth.dto.AuthMeResponseDTO;
import cn.nexus.api.auth.dto.AuthPasswordLoginRequestDTO;
import cn.nexus.api.auth.dto.AuthRefreshRequestDTO;
import cn.nexus.api.auth.dto.AuthRegisterRequestDTO;
import cn.nexus.api.auth.dto.AuthRegisterResponseDTO;
import cn.nexus.api.auth.dto.AuthTokenResponseDTO;
import cn.nexus.api.response.Response;
import cn.nexus.domain.auth.model.valobj.AuthAdminVO;
import cn.nexus.domain.auth.model.valobj.AuthLoginResultVO;
import cn.nexus.domain.auth.model.valobj.AuthMeVO;
import cn.nexus.domain.auth.service.AuthService;
import cn.nexus.trigger.http.support.UserContext;
import cn.nexus.types.enums.ResponseCode;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import cn.dev33.satoken.session.SaSession;

class AuthControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void register_shouldReturnUserId() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        when(authService.register("13800138000", "pwd", "neo", "avatar")).thenReturn(1001L);

        Response<AuthRegisterResponseDTO> response = controller.register(AuthRegisterRequestDTO.builder()
                .phone("13800138000")
                .password("pwd")
                .nickname("neo")
                .avatarUrl("avatar")
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertEquals(1001L, response.getData().getUserId());
    }

    @Test
    void passwordLogin_shouldLoginAndReturnToken() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        when(authService.passwordLogin("13800138000", "pwd")).thenReturn(AuthLoginResultVO.builder()
                .userId(88L)
                .phone("13800138000")
                .build());

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            SaSession issuedRefreshSession = Mockito.mock(SaSession.class);
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-88");
            stpUtil.when(() -> StpUtil.getTokenSessionByToken(Mockito.startsWith("rt_"))).thenReturn(issuedRefreshSession);

            Response<AuthTokenResponseDTO> response = controller.passwordLogin(AuthPasswordLoginRequestDTO.builder()
                    .phone("13800138000")
                    .password("pwd")
                    .build());

            assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
            assertEquals(88L, response.getData().getUserId());
            assertEquals("Authorization", response.getData().getTokenName());
            assertEquals("Bearer", response.getData().getTokenPrefix());
            assertEquals("token-88", response.getData().getToken());
            stpUtil.verify(() -> StpUtil.login(88L));
        }
    }

    @Test
    void refresh_shouldIssueNewTokenWhenRefreshTokenValid() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        when(authService.me(90L)).thenReturn(AuthMeVO.builder().userId(90L).phone("13800138090").build());

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            SaSession session = Mockito.mock(SaSession.class);
            SaSession issuedRefreshSession = Mockito.mock(SaSession.class);
            stpUtil.when(() -> StpUtil.getTokenSessionByToken("rt-90")).thenReturn(session);
            stpUtil.when(() -> StpUtil.getTokenSessionByToken(Mockito.startsWith("rt_"))).thenReturn(issuedRefreshSession);
            when(session.get("refreshUserId")).thenReturn("90");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("access-90");

            Response<AuthTokenResponseDTO> response = controller.refresh(AuthRefreshRequestDTO.builder()
                    .refreshToken("rt-90")
                    .build());

            assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
            assertEquals(90L, response.getData().getUserId());
            assertEquals("access-90", response.getData().getToken());
            assertNotNull(response.getData().getRefreshToken());
            stpUtil.verify(() -> StpUtil.logoutByTokenValue("rt-90"));
            stpUtil.verify(() -> StpUtil.login(90L));
        }
    }

    @Test
    void changePassword_shouldRequireTokenAndInvalidateAllSessions() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        UserContext.setUserId(1003L);
        when(authService.changePassword(1003L, "old-password", "new-password")).thenReturn(1003L);

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            Response<Void> response = controller.changePassword(AuthChangePasswordRequestDTO.builder()
                    .oldPassword("old-password")
                    .newPassword("new-password")
                    .build());

            assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
            assertNull(response.getData());
            verify(authService).changePassword(1003L, "old-password", "new-password");
            stpUtil.verify(() -> StpUtil.logout(1003L));
        }
    }

    @Test
    void changePassword_withoutToken_shouldReturnIllegalParameter() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);

        Response<Void> response = controller.changePassword(AuthChangePasswordRequestDTO.builder()
                .oldPassword("old-password")
                .newPassword("new-password")
                .build());

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertNull(response.getData());
    }

    @Test
    void grantAdmin_shouldDelegateAndReturnSuccess() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        when(authService.grantAdmin(2001L)).thenReturn(2001L);

        Response<Void> response = controller.grantAdmin(AuthGrantAdminRequestDTO.builder()
                .userId(2001L)
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNull(response.getData());
        verify(authService).grantAdmin(2001L);
    }

    @Test
    void revokeAdmin_shouldDelegateAndReturnSuccess() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        when(authService.revokeAdmin(2002L)).thenReturn(2002L);

        Response<Void> response = controller.revokeAdmin(AuthGrantAdminRequestDTO.builder()
                .userId(2002L)
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNull(response.getData());
        verify(authService).revokeAdmin(2002L);
    }

    @Test
    void listAdmins_shouldReturnUserIdsAndDetails() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        when(authService.listAdmins()).thenReturn(java.util.List.of(AuthAdminVO.builder()
                .userId(1001L)
                .phone("13800138000")
                .status("ACTIVE")
                .nickname("neo")
                .avatarUrl("a")
                .build()));

        Response<AuthAdminListResponseDTO> response = controller.listAdmins();

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(java.util.List.of(1001L), response.getData().getUserIds());
        assertNotNull(response.getData().getAdmins());
        assertEquals(1, response.getData().getAdmins().size());
        AuthAdminDTO admin = response.getData().getAdmins().get(0);
        assertEquals(1001L, admin.getUserId());
        assertEquals("13800138000", admin.getPhone());
        assertEquals("ACTIVE", admin.getStatus());
        assertEquals("neo", admin.getNickname());
        assertEquals("a", admin.getAvatarUrl());
    }

    @Test
    void logout_shouldOnlyInvalidateCurrentSession() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            Response<Void> response = controller.logout();

            assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
            stpUtil.verify(StpUtil::logout);
        }
    }

    @Test
    void me_shouldReturnCurrentUser() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        UserContext.setUserId(1004L);
        when(authService.me(1004L)).thenReturn(AuthMeVO.builder()
                .userId(1004L)
                .phone("13800138003")
                .status("ACTIVE")
                .nickname("neo")
                .avatarUrl("a")
                .build());

        Response<AuthMeResponseDTO> response = controller.me();

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(1004L, response.getData().getUserId());
        assertEquals("13800138003", response.getData().getPhone());
        assertEquals("ACTIVE", response.getData().getStatus());
        assertEquals("neo", response.getData().getNickname());
    }

    @Test
    void oldDevLoginRoute_shouldNotExistAnywhere() {
        boolean hasLegacyLoginMethod = Arrays.stream(AuthController.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch("login"::equals);

        assertTrue(!hasLegacyLoginMethod);
    }

    @Test
    void grantAdminRoute_shouldExistAndRequireAdminRole() {
        Method method = Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(candidate -> "grantAdmin".equals(candidate.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(method);
        SaCheckRole annotation = method.getAnnotation(SaCheckRole.class);
        assertNotNull(annotation);
        assertFalse(annotation.value().length == 0);
        assertEquals("ADMIN", annotation.value()[0]);
    }

    @Test
    void revokeAdminRoute_shouldExistAndRequireAdminRole() {
        Method method = Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(candidate -> "revokeAdmin".equals(candidate.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(method);
        SaCheckRole annotation = method.getAnnotation(SaCheckRole.class);
        assertNotNull(annotation);
        assertFalse(annotation.value().length == 0);
        assertEquals("ADMIN", annotation.value()[0]);
    }

    @Test
    void listAdminsRoute_shouldExistAndRequireAdminRole() {
        Method method = Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(candidate -> "listAdmins".equals(candidate.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(method);
        SaCheckRole annotation = method.getAnnotation(SaCheckRole.class);
        assertNotNull(annotation);
        assertFalse(annotation.value().length == 0);
        assertEquals("ADMIN", annotation.value()[0]);
    }
}
