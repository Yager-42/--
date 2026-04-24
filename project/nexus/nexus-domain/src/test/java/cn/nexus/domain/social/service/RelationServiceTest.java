package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IRelationEventOutboxRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.adapter.repository.IUserCounterRepairOutboxRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.valobj.FollowResultVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RelationServiceTest {

    private ISocialIdPort socialIdPort;
    private IRelationRepository relationRepository;
    private IRelationEventOutboxRepository relationEventOutboxRepository;
    private IUserCounterRepairOutboxRepository userCounterRepairOutboxRepository;
    private IRelationPolicyPort relationPolicyPort;
    private IRelationAdjacencyCachePort adjacencyCachePort;
    private IUserCounterService userCounterService;
    private RelationService relationService;

    @BeforeEach
    void setUp() {
        socialIdPort = Mockito.mock(ISocialIdPort.class);
        relationRepository = Mockito.mock(IRelationRepository.class);
        relationEventOutboxRepository = Mockito.mock(IRelationEventOutboxRepository.class);
        userCounterRepairOutboxRepository = Mockito.mock(IUserCounterRepairOutboxRepository.class);
        relationPolicyPort = Mockito.mock(IRelationPolicyPort.class);
        adjacencyCachePort = Mockito.mock(IRelationAdjacencyCachePort.class);
        userCounterService = Mockito.mock(IUserCounterService.class);
        relationService = new RelationService(
                socialIdPort,
                relationRepository,
                relationEventOutboxRepository,
                userCounterRepairOutboxRepository,
                relationPolicyPort,
                adjacencyCachePort,
                userCounterService
        );
    }

    @Test
    void follow_shouldReturnInvalidWhenPairIllegal() {
        FollowResultVO result = relationService.follow(1L, 1L);

        assertEquals("INVALID", result.getStatus());
        verify(relationPolicyPort, never()).isBlocked(any(), any());
        verify(relationRepository, never()).saveRelation(any());
        verify(relationEventOutboxRepository, never()).save(any(), any(), any());
    }

    @Test
    void follow_shouldReturnActiveWhenAlreadyFollowing() {
        when(relationPolicyPort.isBlocked(1L, 2L)).thenReturn(false);
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(RelationEntity.builder().status(1).build());

        FollowResultVO result = relationService.follow(1L, 2L);

        assertEquals("ACTIVE", result.getStatus());
        verify(relationRepository, never()).saveRelation(any());
        verify(relationEventOutboxRepository, never()).save(any(), any(), any());
    }

    @Test
    void unfollow_shouldCleanupResidueWhenNotFollowing() {
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(null);

        FollowResultVO result = relationService.unfollow(1L, 2L);

        assertEquals("NOT_FOLLOWING", result.getStatus());
        verify(relationRepository).deleteFollower(2L, 1L);
        verify(adjacencyCachePort).removeFollow(1L, 2L);
        verify(userCounterService).evict(1L, UserCounterType.FOLLOWING);
        verify(userCounterService).evict(2L, UserCounterType.FOLLOWER);
        verify(relationEventOutboxRepository, never()).save(any(), any(), any());
    }

    @Test
    void block_shouldCleanupBidirectionalFollowAndEmitBlockEvent() {
        when(socialIdPort.nextId()).thenReturn(10L, 20L);
        when(socialIdPort.now()).thenReturn(1000L);
        when(relationRepository.findRelation(1L, 2L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        when(relationRepository.findRelation(2L, 1L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());

        OperationResultVO result = relationService.block(1L, 2L);

        assertTrue(result.isSuccess());
        assertEquals("BLOCKED", result.getStatus());
        verify(relationRepository).saveRelation(any(RelationEntity.class));
        verify(relationRepository).deleteRelation(1L, 2L, 1);
        verify(relationRepository).deleteRelation(2L, 1L, 1);
        verify(relationRepository).deleteFollower(2L, 1L);
        verify(relationRepository).deleteFollower(1L, 2L);
        verify(adjacencyCachePort).removeFollow(1L, 2L);
        verify(adjacencyCachePort).removeFollow(2L, 1L);
        verify(userCounterService).incrementFollowings(1L, -1);
        verify(userCounterService).incrementFollowers(2L, -1);
        verify(userCounterService).incrementFollowings(2L, -1);
        verify(userCounterService).incrementFollowers(1L, -1);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(relationEventOutboxRepository).save(eq(20L), eq("BLOCK"), payloadCaptor.capture());
        assertEquals("{\"eventId\":20,\"sourceId\":1,\"targetId\":2}", payloadCaptor.getValue());
    }

    @Test
    void follow_shouldRecordRepairOutboxWhenUserCounterUpdateFails() {
        when(relationPolicyPort.isBlocked(1L, 2L)).thenReturn(false);
        when(socialIdPort.nextId()).thenReturn(10L, 11L, 12L);
        when(socialIdPort.now()).thenReturn(1000L);
        Mockito.doThrow(new RuntimeException("redis down"))
                .when(userCounterService).incrementFollowings(1L, 1);

        FollowResultVO result = relationService.follow(1L, 2L);

        assertEquals("ACTIVE", result.getStatus());
        verify(relationRepository).saveRelation(any(RelationEntity.class));
        verify(userCounterRepairOutboxRepository).save(1L, 2L, "FOLLOW", "COUNT_REDIS_WRITE_FAILED", "12");
        verify(userCounterRepairOutboxRepository, never()).save(2L, 1L, "FOLLOW", "COUNT_REDIS_WRITE_FAILED", "12");
    }

    @Test
    void follow_shouldNotRecordRepairOutboxWhenUserCounterUpdatesSucceed() {
        when(relationPolicyPort.isBlocked(1L, 2L)).thenReturn(false);
        when(socialIdPort.nextId()).thenReturn(10L, 11L, 12L);
        when(socialIdPort.now()).thenReturn(1000L);

        FollowResultVO result = relationService.follow(1L, 2L);

        assertEquals("ACTIVE", result.getStatus());
        verify(userCounterRepairOutboxRepository, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void block_shouldRecordRepairOutboxOncePerAffectedRelationOnFailure() {
        when(socialIdPort.nextId()).thenReturn(10L, 20L);
        when(socialIdPort.now()).thenReturn(1000L);
        when(relationRepository.findRelation(1L, 2L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        when(relationRepository.findRelation(2L, 1L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        Mockito.doThrow(new RuntimeException("redis down"))
                .when(userCounterService).incrementFollowings(1L, -1);
        Mockito.doThrow(new RuntimeException("redis down"))
                .when(userCounterService).incrementFollowings(2L, -1);

        OperationResultVO result = relationService.block(1L, 2L);

        assertTrue(result.isSuccess());
        verify(userCounterRepairOutboxRepository).save(1L, 2L, "BLOCK", "COUNT_REDIS_WRITE_FAILED", "20");
        verify(userCounterRepairOutboxRepository).save(2L, 1L, "BLOCK", "COUNT_REDIS_WRITE_FAILED", "20");
    }
}
