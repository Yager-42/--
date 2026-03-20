package cn.nexus.trigger.http.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.user.dto.UserInternalUpsertRequestDTO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.user.model.valobj.UserInternalUpsertRequestVO;
import cn.nexus.domain.user.service.UserService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class InternalUserControllerTest {

    @Test
    void upsert_shouldMapDtoAndReturnSuccess() {
        InternalUserController controller = new InternalUserController();
        UserService userService = Mockito.mock(UserService.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
        Mockito.when(userService.internalUpsert(Mockito.any(UserInternalUpsertRequestVO.class)))
                .thenReturn(OperationResultVO.builder().success(true).id(1L).status("OK").message("done").build());

        Response<OperationResultDTO> response = controller.upsert(UserInternalUpsertRequestDTO.builder()
                .userId(1L)
                .username("u1")
                .nickname("n1")
                .avatarUrl("a1")
                .needApproval(true)
                .status("ACTIVE")
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals("OK", response.getData().getStatus());

        ArgumentCaptor<UserInternalUpsertRequestVO> captor = ArgumentCaptor.forClass(UserInternalUpsertRequestVO.class);
        Mockito.verify(userService).internalUpsert(captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertEquals("u1", captor.getValue().getUsername());
        assertEquals("n1", captor.getValue().getNickname());
        assertEquals("a1", captor.getValue().getAvatarUrl());
        assertEquals(Boolean.TRUE, captor.getValue().getNeedApproval());
        assertEquals("ACTIVE", captor.getValue().getStatus());
    }

    @Test
    void upsert_shouldMapNullResultToSentinelDto() {
        InternalUserController controller = new InternalUserController();
        UserService userService = Mockito.mock(UserService.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
        Mockito.when(userService.internalUpsert(Mockito.any(UserInternalUpsertRequestVO.class))).thenReturn(null);

        Response<OperationResultDTO> response = controller.upsert(UserInternalUpsertRequestDTO.builder()
                .userId(1L)
                .username("u1")
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertFalse(response.getData().isSuccess());
        assertEquals("NULL", response.getData().getStatus());
    }

    @Test
    void upsert_shouldTranslateAppException() {
        InternalUserController controller = new InternalUserController();
        UserService userService = Mockito.mock(UserService.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
        Mockito.when(userService.internalUpsert(Mockito.any(UserInternalUpsertRequestVO.class)))
                .thenThrow(new AppException(ResponseCode.CONFLICT.getCode(), ResponseCode.CONFLICT.getInfo()));

        Response<OperationResultDTO> response = controller.upsert(UserInternalUpsertRequestDTO.builder()
                .userId(1L)
                .username("u1")
                .build());

        assertEquals(ResponseCode.CONFLICT.getCode(), response.getCode());
    }
}
