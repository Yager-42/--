package cn.nexus.domain.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.counter.model.valobj.UserRelationCounterVO;
import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;
import cn.nexus.domain.social.service.IRiskService;
import cn.nexus.domain.user.adapter.repository.IUserProfileRepository;
import cn.nexus.domain.user.adapter.repository.IUserStatusRepository;
import cn.nexus.domain.user.model.valobj.UserProfilePageVO;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UserProfilePageQueryServiceTest {

    @Test
    void query_blocked_shouldThrowNotFound() {
        IUserProfileRepository userProfileRepository = Mockito.mock(IUserProfileRepository.class);
        IUserStatusRepository userStatusRepository = Mockito.mock(IUserStatusRepository.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        IRelationPolicyPort relationPolicyPort = Mockito.mock(IRelationPolicyPort.class);
        IRiskService riskService = Mockito.mock(IRiskService.class);
        Executor aggregationExecutor = Runnable::run;

        UserProfilePageQueryService svc = new UserProfilePageQueryService(
                userProfileRepository,
                userStatusRepository,
                relationRepository,
                userCounterService,
                relationPolicyPort,
                riskService,
                aggregationExecutor);

        when(relationPolicyPort.isBlocked(1L, 2L)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> svc.query(1L, 2L));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void query_normal_shouldAggregateFields() {
        IUserProfileRepository userProfileRepository = Mockito.mock(IUserProfileRepository.class);
        IUserStatusRepository userStatusRepository = Mockito.mock(IUserStatusRepository.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        IRelationPolicyPort relationPolicyPort = Mockito.mock(IRelationPolicyPort.class);
        IRiskService riskService = Mockito.mock(IRiskService.class);
        Executor aggregationExecutor = Runnable::run;

        UserProfilePageQueryService svc = new UserProfilePageQueryService(
                userProfileRepository,
                userStatusRepository,
                relationRepository,
                userCounterService,
                relationPolicyPort,
                riskService,
                aggregationExecutor);

        when(relationPolicyPort.isBlocked(1L, 2L)).thenReturn(false);
        when(relationPolicyPort.isBlocked(2L, 1L)).thenReturn(false);
        when(userProfileRepository.get(2L)).thenReturn(UserProfileVO.builder()
                .userId(2L)
                .username("u2")
                .nickname("n2")
                .avatarUrl("a2")
                .build());
        when(userStatusRepository.getStatus(2L)).thenReturn("ACTIVE");
        when(userCounterService.readRelationCountersWithVerification(2L)).thenReturn(
                UserRelationCounterVO.builder()
                        .followings(11L)
                        .followers(22L)
                        .posts(33L)
                        .likesReceived(44L)
                        .favsReceived(55L)
                        .build()
        );
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(RelationEntity.builder().id(10L).status(1).build());
        when(riskService.userStatus(2L)).thenReturn(UserRiskStatusVO.builder()
                .status("NORMAL")
                .capabilities(List.of("POST", "COMMENT"))
                .build());

        UserProfilePageVO res = svc.query(1L, 2L);
        assertNotNull(res);
        assertNotNull(res.getProfile());
        assertEquals(2L, res.getProfile().getUserId());
        assertEquals("ACTIVE", res.getStatus());
        assertNotNull(res.getRelation());
        assertEquals(11L, res.getRelation().getFollowings());
        assertEquals(22L, res.getRelation().getFollowers());
        assertEquals(33L, res.getRelation().getPosts());
        assertEquals(44L, res.getRelation().getLikesReceived());
        assertEquals(55L, res.getRelation().getFavsReceived());
        assertEquals(true, res.getRelation().isFollow());
        assertNotNull(res.getRisk());
        assertEquals("NORMAL", res.getRisk().getStatus());
    }

    @Test
    void query_selfProfile_shouldSkipBlockAndRelationLookup() {
        IUserProfileRepository userProfileRepository = Mockito.mock(IUserProfileRepository.class);
        IUserStatusRepository userStatusRepository = Mockito.mock(IUserStatusRepository.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        IRelationPolicyPort relationPolicyPort = Mockito.mock(IRelationPolicyPort.class);
        IRiskService riskService = Mockito.mock(IRiskService.class);
        Executor aggregationExecutor = Runnable::run;

        UserProfilePageQueryService svc = new UserProfilePageQueryService(
                userProfileRepository,
                userStatusRepository,
                relationRepository,
                userCounterService,
                relationPolicyPort,
                riskService,
                aggregationExecutor);

        when(userProfileRepository.get(1L)).thenReturn(UserProfileVO.builder()
                .userId(1L)
                .username("u1")
                .nickname("n1")
                .avatarUrl("a1")
                .build());
        when(userStatusRepository.getStatus(1L)).thenReturn("ACTIVE");
        when(userCounterService.readRelationCountersWithVerification(1L)).thenReturn(
                UserRelationCounterVO.builder()
                        .followings(2L)
                        .followers(3L)
                        .posts(4L)
                        .likesReceived(5L)
                        .favsReceived(6L)
                        .build()
        );
        when(riskService.userStatus(1L)).thenReturn(UserRiskStatusVO.builder().status("NORMAL").build());

        UserProfilePageVO res = svc.query(1L, 1L);

        assertEquals(false, res.getRelation().isFollow());
        verify(relationPolicyPort, never()).isBlocked(Mockito.anyLong(), Mockito.anyLong());
        verify(relationRepository, never()).findRelation(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    void query_dependencyFailed_shouldKeepOriginalExceptionType() {
        IUserProfileRepository userProfileRepository = Mockito.mock(IUserProfileRepository.class);
        IUserStatusRepository userStatusRepository = Mockito.mock(IUserStatusRepository.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        IRelationPolicyPort relationPolicyPort = Mockito.mock(IRelationPolicyPort.class);
        IRiskService riskService = Mockito.mock(IRiskService.class);
        Executor aggregationExecutor = Runnable::run;

        UserProfilePageQueryService svc = new UserProfilePageQueryService(
                userProfileRepository,
                userStatusRepository,
                relationRepository,
                userCounterService,
                relationPolicyPort,
                riskService,
                aggregationExecutor);

        when(relationPolicyPort.isBlocked(1L, 2L)).thenReturn(false);
        when(relationPolicyPort.isBlocked(2L, 1L)).thenReturn(false);
        when(userProfileRepository.get(2L)).thenReturn(UserProfileVO.builder()
                .userId(2L)
                .username("u2")
                .nickname("n2")
                .avatarUrl("a2")
                .build());
        when(userStatusRepository.getStatus(2L)).thenReturn("ACTIVE");
        when(userCounterService.readRelationCountersWithVerification(2L)).thenReturn(
                UserRelationCounterVO.builder()
                        .followings(11L)
                        .followers(22L)
                        .posts(0L)
                        .likesReceived(0L)
                        .favsReceived(0L)
                        .build()
        );
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(RelationEntity.builder().id(10L).status(1).build());
        when(riskService.userStatus(2L)).thenThrow(new AppException(ResponseCode.UN_ERROR.getCode(), "risk boom"));

        AppException ex = assertThrows(AppException.class, () -> svc.query(1L, 2L));
        assertEquals(ResponseCode.UN_ERROR.getCode(), ex.getCode());
    }
}
