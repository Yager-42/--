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
import cn.nexus.api.auth.dto.AuthRegisterRequestDTO;
import cn.nexus.api.auth.dto.AuthRegisterResponseDTO;
import cn.nexus.api.auth.dto.AuthSmsLoginRequestDTO;
import cn.nexus.api.auth.dto.AuthSmsSendRequestDTO;
import cn.nexus.api.auth.dto.AuthSmsSendResponseDTO;
import cn.nexus.api.auth.dto.AuthTokenResponseDTO;
import cn.nexus.api.response.Response;
import cn.nexus.domain.auth.model.valobj.AuthAdminVO;
import cn.nexus.domain.auth.model.valobj.AuthLoginResultVO;
import cn.nexus.domain.auth.model.valobj.AuthMeVO;
import cn.nexus.domain.auth.model.valobj.AuthSmsBizTypeVO;
import cn.nexus.domain.auth.service.AuthService;
import cn.nexus.trigger.http.support.UserContext;
import cn.nexus.types.enums.ResponseCode;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class AuthControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void sendSms_shouldDelegateAndReturnExpireSeconds() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);

        Response<AuthSmsSendResponseDTO> response = controller.sendSms(AuthSmsSendRequestDTO.builder()
                .phone("13800138000")
                .bizType("REGISTER")
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertEquals(300, response.getData().getExpireSeconds());
        verify(authService).sendSms(Mockito.eq("13800138000"), Mockito.eq(AuthSmsBizTypeVO.REGISTER), Mockito.anyString());
    }

    @Test
    void register_shouldReturnUserId() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        when(authService.register("13800138000", "123456", "pwd", "neo", "avatar")).thenReturn(1001L);

        Response<AuthRegisterResponseDTO> response = controller.register(AuthRegisterRequestDTO.builder()
                .phone("13800138000")
                .smsCode("123456")
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
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-88");

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
    void smsLogin_shouldLoginAndReturnToken() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        when(authService.smsLogin("13800138001", "888888")).thenReturn(AuthLoginResultVO.builder()
                .userId(89L)
                .phone("13800138001")
                .build());

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-89");

            Response<AuthTokenResponseDTO> response = controller.smsLogin(AuthSmsLoginRequestDTO.builder()
                    .phone("13800138001")
                    .smsCode("888888")
                    .build());

            assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
            assertEquals(89L, response.getData().getUserId());
            assertEquals("token-89", response.getData().getToken());
            stpUtil.verify(() -> StpUtil.login(89L));
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
