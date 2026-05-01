package cn.nexus.trigger.http.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.action.dto.ActionRequestDTO;
import cn.nexus.api.social.action.dto.ActionResponseDTO;
import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.social.model.valobj.PostActionResultVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.trigger.http.support.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class ActionControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void likeShouldAcceptOnlyPostTargetAndReturnFullActionState() {
        ActionController controller = controller();
        IObjectCounterService service = service(controller);
        UserContext.setUserId(7L);
        when(service.likePost(42L, 7L)).thenReturn(result(true, true, false, 5L, 2L));

        Response<ActionResponseDTO> response = controller.like(ActionRequestDTO.builder()
                .targetType("post")
                .targetId(42L)
                .requestId("r1")
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(true, response.getData().isChanged());
        assertEquals(true, response.getData().isLiked());
        assertEquals(false, response.getData().isFaved());
        assertEquals(5L, response.getData().getLikeCount());
        assertEquals(2L, response.getData().getFavoriteCount());
        verify(service).likePost(42L, 7L);
    }

    @Test
    void unsupportedTargetShouldReturnIllegalParameterBeforeServiceCall() {
        ActionController controller = controller();
        IObjectCounterService service = service(controller);
        UserContext.setUserId(7L);

        Response<ActionResponseDTO> response = controller.like(ActionRequestDTO.builder()
                .targetType("comment")
                .targetId(42L)
                .build());

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        verify(service, never()).likePost(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    void unlikeFavAndUnfavShouldDelegateToConcretePostMethods() {
        ActionController controller = controller();
        IObjectCounterService service = service(controller);
        UserContext.setUserId(7L);
        when(service.unlikePost(42L, 7L)).thenReturn(result(true, false, false, 4L, 1L));
        when(service.favPost(42L, 7L)).thenReturn(result(false, false, true, 4L, 1L));
        when(service.unfavPost(42L, 7L)).thenReturn(result(true, false, false, 4L, 0L));
        ActionRequestDTO request = ActionRequestDTO.builder().targetType("post").targetId(42L).build();

        assertEquals(false, controller.unlike(request).getData().isLiked());
        assertEquals(true, controller.fav(request).getData().isFaved());
        assertEquals(false, controller.unfav(request).getData().isFaved());

        verify(service).unlikePost(42L, 7L);
        verify(service).favPost(42L, 7L);
        verify(service).unfavPost(42L, 7L);
    }

    private static ActionController controller() {
        ActionController controller = new ActionController();
        ReflectionTestUtils.setField(controller, "objectCounterService", Mockito.mock(IObjectCounterService.class));
        return controller;
    }

    private static IObjectCounterService service(ActionController controller) {
        return (IObjectCounterService) ReflectionTestUtils.getField(controller, "objectCounterService");
    }

    private static PostActionResultVO result(boolean changed, boolean liked, boolean faved,
                                             long likeCount, long favoriteCount) {
        return PostActionResultVO.builder()
                .changed(changed)
                .liked(liked)
                .faved(faved)
                .likeCount(likeCount)
                .favoriteCount(favoriteCount)
                .build();
    }
}
