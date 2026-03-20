package cn.nexus.trigger.http.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.user.dto.UserProfileQueryRequestDTO;
import cn.nexus.api.user.dto.UserProfileResponseDTO;
import cn.nexus.api.user.dto.UserProfileUpdateRequestDTO;
import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.user.adapter.repository.IUserProfileRepository;
import cn.nexus.domain.user.adapter.repository.IUserStatusRepository;
import cn.nexus.domain.user.model.valobj.UserProfilePatchVO;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.domain.user.service.UserService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.trigger.http.support.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class UserProfileControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void myProfile_shouldReturnCurrentUserProfile() {
        UserProfileController controller = new UserProfileController();
        UserService userService = Mockito.mock(UserService.class);
        IUserProfileRepository userProfileRepository = Mockito.mock(IUserProfileRepository.class);
        IUserStatusRepository userStatusRepository = Mockito.mock(IUserStatusRepository.class);
        IRelationPolicyPort relationPolicyPort = Mockito.mock(IRelationPolicyPort.class);
        inject(controller, userService, userProfileRepository, userStatusRepository, relationPolicyPort);

        UserContext.setUserId(10L);
        when(userProfileRepository.get(10L)).thenReturn(profileVo(10L, "u10", "nick10"));
        when(userStatusRepository.getStatus(10L)).thenReturn("ACTIVE");

        Response<UserProfileResponseDTO> response = controller.myProfile();

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertEquals(10L, response.getData().getUserId());
        assertEquals("u10", response.getData().getUsername());
        assertEquals("nick10", response.getData().getNickname());
        assertEquals("ACTIVE", response.getData().getStatus());
    }

    @Test
    void profile_shouldReturnNotFoundWhenBlocked() {
        UserProfileController controller = new UserProfileController();
        UserService userService = Mockito.mock(UserService.class);
        IUserProfileRepository userProfileRepository = Mockito.mock(IUserProfileRepository.class);
        IUserStatusRepository userStatusRepository = Mockito.mock(IUserStatusRepository.class);
        IRelationPolicyPort relationPolicyPort = Mockito.mock(IRelationPolicyPort.class);
        inject(controller, userService, userProfileRepository, userStatusRepository, relationPolicyPort);

        UserContext.setUserId(1L);
        when(relationPolicyPort.isBlocked(1L, 2L)).thenReturn(true);

        Response<UserProfileResponseDTO> response = controller.profile(UserProfileQueryRequestDTO.builder()
                .targetUserId(2L)
                .build());

        assertEquals(ResponseCode.NOT_FOUND.getCode(), response.getCode());
        verify(userProfileRepository, never()).get(Mockito.anyLong());
    }

    @Test
    void profile_shouldReturnIllegalParameterWhenTargetMissing() {
        UserProfileController controller = new UserProfileController();
        inject(controller, Mockito.mock(UserService.class), Mockito.mock(IUserProfileRepository.class),
                Mockito.mock(IUserStatusRepository.class), Mockito.mock(IRelationPolicyPort.class));
        UserContext.setUserId(1L);

        Response<UserProfileResponseDTO> response = controller.profile(UserProfileQueryRequestDTO.builder().build());

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
    }

    @Test
    void updateMyProfile_shouldMapPatchAndReturnResult() {
        UserProfileController controller = new UserProfileController();
        UserService userService = Mockito.mock(UserService.class);
        inject(controller, userService, Mockito.mock(IUserProfileRepository.class),
                Mockito.mock(IUserStatusRepository.class), Mockito.mock(IRelationPolicyPort.class));
        UserContext.setUserId(9L);
        when(userService.updateMyProfile(eq(9L), Mockito.any(UserProfilePatchVO.class)))
                .thenReturn(OperationResultVO.builder().success(true).id(9L).status("OK").message("done").build());

        Response<OperationResultDTO> response = controller.updateMyProfile(UserProfileUpdateRequestDTO.builder()
                .nickname("new-nick")
                .avatarUrl("")
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertEquals("OK", response.getData().getStatus());
        assertEquals("done", response.getData().getMessage());

        ArgumentCaptor<UserProfilePatchVO> captor = ArgumentCaptor.forClass(UserProfilePatchVO.class);
        verify(userService).updateMyProfile(eq(9L), captor.capture());
        assertEquals("new-nick", captor.getValue().getNickname());
        assertEquals("", captor.getValue().getAvatarUrl());
    }

    @Test
    void updateMyProfile_shouldMapNullResultToSentinelDto() {
        UserProfileController controller = new UserProfileController();
        UserService userService = Mockito.mock(UserService.class);
        inject(controller, userService, Mockito.mock(IUserProfileRepository.class),
                Mockito.mock(IUserStatusRepository.class), Mockito.mock(IRelationPolicyPort.class));
        UserContext.setUserId(9L);
        when(userService.updateMyProfile(eq(9L), Mockito.any(UserProfilePatchVO.class))).thenReturn(null);

        Response<OperationResultDTO> response = controller.updateMyProfile(UserProfileUpdateRequestDTO.builder()
                .nickname("n")
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertFalse(response.getData().isSuccess());
        assertEquals("NULL", response.getData().getStatus());
    }

    @Test
    void myProfile_shouldReturnIllegalParameterWhenUserContextMissing() {
        UserProfileController controller = new UserProfileController();
        inject(controller, Mockito.mock(UserService.class), Mockito.mock(IUserProfileRepository.class),
                Mockito.mock(IUserStatusRepository.class), Mockito.mock(IRelationPolicyPort.class));

        Response<UserProfileResponseDTO> response = controller.myProfile();

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertNull(response.getData());
    }

    private void inject(UserProfileController controller,
                        UserService userService,
                        IUserProfileRepository userProfileRepository,
                        IUserStatusRepository userStatusRepository,
                        IRelationPolicyPort relationPolicyPort) {
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userProfileRepository", userProfileRepository);
        ReflectionTestUtils.setField(controller, "userStatusRepository", userStatusRepository);
        ReflectionTestUtils.setField(controller, "relationPolicyPort", relationPolicyPort);
    }

    private UserProfileVO profileVo(Long userId, String username, String nickname) {
        return UserProfileVO.builder()
                .userId(userId)
                .username(username)
                .nickname(nickname)
                .avatarUrl("avatar")
                .build();
    }
}
