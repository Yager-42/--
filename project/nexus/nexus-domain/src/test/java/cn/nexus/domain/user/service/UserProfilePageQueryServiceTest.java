package cn.nexus.domain.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IRelationCachePort;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UserProfilePageQueryServiceTest {

    @Test
    void query_blocked_shouldThrowNotFound() {
        IUserProfileRepository userProfileRepository = Mockito.mock(IUserProfileRepository.class);
        IUserStatusRepository userStatusRepository = Mockito.mock(IUserStatusRepository.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IRelationCachePort relationCachePort = Mockito.mock(IRelationCachePort.class);
        IRelationPolicyPort relationPolicyPort = Mockito.mock(IRelationPolicyPort.class);
        IRiskService riskService = Mockito.mock(IRiskService.class);

        UserProfilePageQueryService svc = new UserProfilePageQueryService(
                userProfileRepository,
                userStatusRepository,
                relationRepository,
                relationCachePort,
                relationPolicyPort,
                riskService);

        when(relationPolicyPort.isBlocked(1L, 2L)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> svc.query(1L, 2L));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void query_normal_shouldAggregateFields() {
        IUserProfileRepository userProfileRepository = Mockito.mock(IUserProfileRepository.class);
        IUserStatusRepository userStatusRepository = Mockito.mock(IUserStatusRepository.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IRelationCachePort relationCachePort = Mockito.mock(IRelationCachePort.class);
        IRelationPolicyPort relationPolicyPort = Mockito.mock(IRelationPolicyPort.class);
        IRiskService riskService = Mockito.mock(IRiskService.class);

        UserProfilePageQueryService svc = new UserProfilePageQueryService(
                userProfileRepository,
                userStatusRepository,
                relationRepository,
                relationCachePort,
                relationPolicyPort,
                riskService);

        when(relationPolicyPort.isBlocked(1L, 2L)).thenReturn(false);
        when(relationPolicyPort.isBlocked(2L, 1L)).thenReturn(false);
        when(userProfileRepository.get(2L)).thenReturn(UserProfileVO.builder()
                .userId(2L)
                .username("u2")
                .nickname("n2")
                .avatarUrl("a2")
                .build());
        when(userStatusRepository.getStatus(2L)).thenReturn("ACTIVE");
        when(relationCachePort.getFollowCount(2L)).thenReturn(11L);
        when(relationRepository.countFollowerIds(2L)).thenReturn(22);
        when(relationRepository.countRelationsBySource(2L, 2)).thenReturn(3);
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(RelationEntity.builder().id(10L).build());
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
        assertEquals(11L, res.getRelation().getFollowCount());
        assertEquals(22L, res.getRelation().getFollowerCount());
        assertEquals(3L, res.getRelation().getFriendCount());
        assertEquals(true, res.getRelation().isFollow());
        assertNotNull(res.getRisk());
        assertEquals("NORMAL", res.getRisk().getStatus());
    }
}

