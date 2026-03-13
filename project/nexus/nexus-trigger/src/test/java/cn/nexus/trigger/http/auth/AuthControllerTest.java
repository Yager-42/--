package cn.nexus.trigger.http.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import cn.nexus.api.auth.dto.AuthLoginRequestDTO;
import cn.nexus.api.auth.dto.AuthLoginResponseDTO;
import cn.nexus.api.response.Response;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import cn.nexus.types.enums.ResponseCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class AuthControllerTest {

    @Test
    void login_shouldReuseExistingUserAndReturnToken() {
        IUserBaseDao userBaseDao = Mockito.mock(IUserBaseDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        AuthController controller = new AuthController(userBaseDao, socialIdPort);

        UserBasePO existed = new UserBasePO();
        existed.setUserId(11L);
        existed.setUsername("u11");
        when(userBaseDao.selectByUserId(11L)).thenReturn(existed);

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-11");

            Response<AuthLoginResponseDTO> response = controller.login(AuthLoginRequestDTO.builder()
                    .userId(11L)
                    .username("u11")
                    .build());

            assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
            assertNotNull(response.getData());
            assertEquals(11L, response.getData().getUserId());
            assertEquals("Authorization", response.getData().getTokenName());
            assertEquals("Bearer", response.getData().getTokenPrefix());
            assertEquals("token-11", response.getData().getToken());
            verify(userBaseDao, never()).insert(Mockito.any());
            stpUtil.verify(() -> StpUtil.login(11L));
        }
    }

    @Test
    void login_shouldCreateNewUserWithDefaultsWhenUserNotExists() {
        IUserBaseDao userBaseDao = Mockito.mock(IUserBaseDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        AuthController controller = new AuthController(userBaseDao, socialIdPort);

        when(socialIdPort.nextId()).thenReturn(88L);

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-88");

            Response<AuthLoginResponseDTO> response = controller.login(AuthLoginRequestDTO.builder()
                    .userId(88L)
                    .username("   ")
                    .nickname("   ")
                    .avatarUrl(null)
                    .build());

            assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
            assertEquals(88L, response.getData().getUserId());

            ArgumentCaptor<UserBasePO> captor = ArgumentCaptor.forClass(UserBasePO.class);
            verify(userBaseDao).insert(captor.capture());
            UserBasePO inserted = captor.getValue();
            assertEquals(88L, inserted.getUserId());
            assertEquals("u88", inserted.getUsername());
            assertEquals("u88", inserted.getNickname());
            assertEquals("", inserted.getAvatarUrl());
            stpUtil.verify(() -> StpUtil.login(88L));
        }
    }

    @Test
    void login_insertConflictShouldRetryByUsername() {
        IUserBaseDao userBaseDao = Mockito.mock(IUserBaseDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        AuthController controller = new AuthController(userBaseDao, socialIdPort);

        when(socialIdPort.nextId()).thenReturn(99L);
        Mockito.doThrow(new RuntimeException("duplicate"))
                .when(userBaseDao)
                .insert(Mockito.any(UserBasePO.class));
        UserBasePO retry = new UserBasePO();
        retry.setUserId(100L);
        retry.setUsername("tom");
        when(userBaseDao.selectByUsername("tom")).thenReturn(retry);

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-100");

            Response<AuthLoginResponseDTO> response = controller.login(AuthLoginRequestDTO.builder()
                    .username("tom")
                    .build());

            assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
            assertEquals(100L, response.getData().getUserId());
            stpUtil.verify(() -> StpUtil.login(100L));
        }
    }

    @Test
    void login_missingUserIdAndUsername_shouldReturnIllegalParameter() {
        IUserBaseDao userBaseDao = Mockito.mock(IUserBaseDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        AuthController controller = new AuthController(userBaseDao, socialIdPort);

        Response<AuthLoginResponseDTO> response = controller.login(AuthLoginRequestDTO.builder().build());

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertNull(response.getData());
    }

    @Test
    void login_existingUserConflict_shouldReturnConflict() {
        IUserBaseDao userBaseDao = Mockito.mock(IUserBaseDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        AuthController controller = new AuthController(userBaseDao, socialIdPort);

        UserBasePO existed = new UserBasePO();
        existed.setUserId(1L);
        existed.setUsername("real");
        when(userBaseDao.selectByUsername("fake")).thenReturn(existed);

        Response<AuthLoginResponseDTO> response = controller.login(AuthLoginRequestDTO.builder()
                .username("fake")
                .userId(2L)
                .build());

        assertEquals(ResponseCode.CONFLICT.getCode(), response.getCode());
        assertNull(response.getData());
    }
}
