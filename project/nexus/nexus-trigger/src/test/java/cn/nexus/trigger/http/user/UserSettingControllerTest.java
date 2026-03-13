package cn.nexus.trigger.http.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.user.dto.UserPrivacyResponseDTO;
import cn.nexus.api.user.dto.UserPrivacyUpdateRequestDTO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.user.adapter.repository.IUserPrivacyRepository;
import cn.nexus.domain.user.adapter.repository.IUserProfileRepository;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.domain.user.service.UserService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.trigger.http.support.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class UserSettingControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void myPrivacy_shouldFallbackToFalseWhenSettingMissing() {
        UserSettingController controller = new UserSettingController();
        IUserProfileRepository userProfileRepository = Mockito.mock(IUserProfileRepository.class);
        IUserPrivacyRepository userPrivacyRepository = Mockito.mock(IUserPrivacyRepository.class);
        inject(controller, Mockito.mock(UserService.class), userProfileRepository, userPrivacyRepository);
        UserContext.setUserId(3L);

        when(userProfileRepository.get(3L)).thenReturn(UserProfileVO.builder().userId(3L).username("u3").build());
        when(userPrivacyRepository.getNeedApproval(3L)).thenReturn(null);

        Response<UserPrivacyResponseDTO> response = controller.myPrivacy();

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertEquals(Boolean.FALSE, response.getData().getNeedApproval());
    }

    @Test
    void myPrivacy_shouldReturnNotFoundWhenProfileMissing() {
        UserSettingController controller = new UserSettingController();
        IUserProfileRepository userProfileRepository = Mockito.mock(IUserProfileRepository.class);
        inject(controller, Mockito.mock(UserService.class), userProfileRepository, Mockito.mock(IUserPrivacyRepository.class));
        UserContext.setUserId(3L);

        when(userProfileRepository.get(3L)).thenReturn(null);

        Response<UserPrivacyResponseDTO> response = controller.myPrivacy();

        assertEquals(ResponseCode.NOT_FOUND.getCode(), response.getCode());
    }

    @Test
    void updateMyPrivacy_shouldDelegateToService() {
        UserSettingController controller = new UserSettingController();
        UserService userService = Mockito.mock(UserService.class);
        inject(controller, userService, Mockito.mock(IUserProfileRepository.class), Mockito.mock(IUserPrivacyRepository.class));
        UserContext.setUserId(7L);
        when(userService.updateMyPrivacy(7L, true))
                .thenReturn(OperationResultVO.builder().success(true).id(7L).status("OK").message("saved").build());

        Response<OperationResultDTO> response = controller.updateMyPrivacy(UserPrivacyUpdateRequestDTO.builder()
                .needApproval(true)
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertEquals("OK", response.getData().getStatus());
        verify(userService).updateMyPrivacy(eq(7L), eq(true));
    }

    @Test
    void updateMyPrivacy_shouldMapNullResultToSentinelDto() {
        UserSettingController controller = new UserSettingController();
        UserService userService = Mockito.mock(UserService.class);
        inject(controller, userService, Mockito.mock(IUserProfileRepository.class), Mockito.mock(IUserPrivacyRepository.class));
        UserContext.setUserId(7L);
        when(userService.updateMyPrivacy(7L, false)).thenReturn(null);

        Response<OperationResultDTO> response = controller.updateMyPrivacy(UserPrivacyUpdateRequestDTO.builder()
                .needApproval(false)
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertFalse(response.getData().isSuccess());
        assertEquals("NULL", response.getData().getStatus());
    }

    private void inject(UserSettingController controller,
                        UserService userService,
                        IUserProfileRepository userProfileRepository,
                        IUserPrivacyRepository userPrivacyRepository) {
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userProfileRepository", userProfileRepository);
        ReflectionTestUtils.setField(controller, "userPrivacyRepository", userPrivacyRepository);
    }
}
