package cn.nexus.trigger.http.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.counter.dto.PostCounterResponseDTO;
import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.types.enums.ResponseCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class CounterControllerTest {

    @Test
    void postCounterShouldReturnOnlyRequestedActiveMetrics() {
        CounterController controller = controller();
        IObjectCounterService service = service(controller);
        when(service.getPostCounts(42L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV)))
                .thenReturn(Map.of("like", 5L, "fav", 2L));

        Response<PostCounterResponseDTO> response = controller.postCounter(42L, "like,fav");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(42L, response.getData().getPostId());
        assertEquals(Map.of("like", 5L, "fav", 2L), response.getData().getCounts());
        verify(service).getPostCounts(42L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));
    }

    @Test
    void unsupportedMetricShouldReturnIllegalParameterBeforeServiceCall() {
        CounterController controller = controller();
        IObjectCounterService service = service(controller);

        Response<PostCounterResponseDTO> response = controller.postCounter(42L, "like,comment");

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        verify(service, never()).getPostCounts(Mockito.anyLong(), Mockito.any());
    }

    private static CounterController controller() {
        CounterController controller = new CounterController();
        ReflectionTestUtils.setField(controller, "objectCounterService", Mockito.mock(IObjectCounterService.class));
        return controller;
    }

    private static IObjectCounterService service(CounterController controller) {
        return (IObjectCounterService) ReflectionTestUtils.getField(controller, "objectCounterService");
    }
}
