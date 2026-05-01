package cn.nexus.trigger.http.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.relation.dto.RelationCounterResponseDTO;
import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.counter.model.valobj.UserRelationCounterVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.trigger.http.support.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class RelationControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void counter_shouldReadCurrentUserCountersWithVerification() {
        RelationController controller = new RelationController();
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        ReflectionTestUtils.setField(controller, "userCounterService", userCounterService);
        UserContext.setUserId(7L);
        when(userCounterService.readRelationCountersWithVerification(7L))
                .thenReturn(UserRelationCounterVO.builder()
                        .followings(1L)
                        .followers(2L)
                        .posts(3L)
                        .likesReceived(4L)
                        .favsReceived(5L)
                        .build());

        Response<RelationCounterResponseDTO> response = controller.counter();

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertEquals(1L, response.getData().getFollowings());
        assertEquals(2L, response.getData().getFollowers());
        assertEquals(3L, response.getData().getPosts());
        assertEquals(4L, response.getData().getLikesReceived());
        assertEquals(5L, response.getData().getFavsReceived());
        verify(userCounterService).readRelationCountersWithVerification(7L);
    }
}
