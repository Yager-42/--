package cn.nexus.trigger.http.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import cn.nexus.api.response.Response;
import cn.nexus.api.user.dto.UserProfilePageResponseDTO;
import cn.nexus.api.user.dto.UserProfileQueryRequestDTO;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;
import cn.nexus.domain.user.model.valobj.UserProfilePageVO;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.domain.user.model.valobj.UserRelationStatsVO;
import cn.nexus.domain.user.service.UserProfilePageQueryService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.trigger.http.support.UserContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class UserProfilePageControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void profilePage_shouldMapAggregatedView() {
        UserProfilePageController controller = new UserProfilePageController();
        UserProfilePageQueryService queryService = Mockito.mock(UserProfilePageQueryService.class);
        ReflectionTestUtils.setField(controller, "userProfilePageQueryService", queryService);
        UserContext.setUserId(1L);

        when(queryService.query(1L, 2L)).thenReturn(UserProfilePageVO.builder()
                .profile(UserProfileVO.builder()
                        .userId(2L)
                        .username("u2")
                        .nickname("n2")
                        .avatarUrl("a2")
                        .build())
                .status("ACTIVE")
                .relation(UserRelationStatsVO.builder()
                        .followings(5L)
                        .followers(6L)
                        .posts(7L)
                        .likedPosts(8L)
                        .isFollow(true)
                        .build())
                .risk(UserRiskStatusVO.builder().status("NORMAL").capabilities(List.of("POST")).build())
                .build());

        Response<UserProfilePageResponseDTO> response = controller.profilePage(UserProfileQueryRequestDTO.builder()
                .targetUserId(2L)
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertEquals("u2", response.getData().getProfile().getUsername());
        assertEquals("ACTIVE", response.getData().getProfile().getStatus());
        assertEquals(5L, response.getData().getRelation().getFollowings());
        assertEquals(6L, response.getData().getRelation().getFollowers());
        assertEquals(7L, response.getData().getRelation().getPosts());
        assertEquals(8L, response.getData().getRelation().getLikedPosts());
        assertEquals(true, response.getData().getRelation().isFollow());
        assertEquals("NORMAL", response.getData().getRisk().getStatus());
    }

    @Test
    void profilePage_shouldReturnIllegalParameterWhenTargetMissing() {
        UserProfilePageController controller = new UserProfilePageController();
        ReflectionTestUtils.setField(controller, "userProfilePageQueryService", Mockito.mock(UserProfilePageQueryService.class));
        UserContext.setUserId(1L);

        Response<UserProfilePageResponseDTO> response = controller.profilePage(UserProfileQueryRequestDTO.builder().build());

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
    }

    @Test
    void profilePage_shouldAllowNullAggregate() {
        UserProfilePageController controller = new UserProfilePageController();
        UserProfilePageQueryService queryService = Mockito.mock(UserProfilePageQueryService.class);
        ReflectionTestUtils.setField(controller, "userProfilePageQueryService", queryService);
        UserContext.setUserId(1L);
        when(queryService.query(1L, 2L)).thenReturn(null);

        Response<UserProfilePageResponseDTO> response = controller.profilePage(UserProfileQueryRequestDTO.builder()
                .targetUserId(2L)
                .build());

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNull(response.getData());
    }
}
