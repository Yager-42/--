package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IRelationEventOutboxRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
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
    private IRelationPolicyPort relationPolicyPort;
    private RelationService relationService;

    @BeforeEach
    void setUp() {
        socialIdPort = Mockito.mock(ISocialIdPort.class);
        relationRepository = Mockito.mock(IRelationRepository.class);
        relationEventOutboxRepository = Mockito.mock(IRelationEventOutboxRepository.class);
        relationPolicyPort = Mockito.mock(IRelationPolicyPort.class);
        relationService = new RelationService(
                socialIdPort,
                relationRepository,
                relationEventOutboxRepository,
                relationPolicyPort
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
    void follow_shouldPersistTruthAndOutboxWithoutFollowerOrCounterSideEffects() {
        when(relationPolicyPort.isBlocked(1L, 2L)).thenReturn(false);
        when(socialIdPort.nextId()).thenReturn(10L, 12L);
        when(socialIdPort.now()).thenReturn(1000L);

        FollowResultVO result = relationService.follow(1L, 2L);

        assertEquals("ACTIVE", result.getStatus());
        verify(relationRepository).saveRelation(any(RelationEntity.class));
        verify(relationRepository, never()).saveFollower(any(), any(), any(), any());
        verify(relationEventOutboxRepository).save(eq(12L), eq("FOLLOW"), any(String.class));
    }

    @Test
    void unfollow_shouldReturnNotFollowingWhenNoActiveRelation() {
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(null);

        FollowResultVO result = relationService.unfollow(1L, 2L);

        assertEquals("NOT_FOLLOWING", result.getStatus());
        verify(relationRepository, never()).deleteFollower(any(), any());
        verify(relationEventOutboxRepository, never()).save(any(), any(), any());
    }

    @Test
    void unfollow_shouldPersistTruthAndOutboxWithoutFollowerOrCounterSideEffects() {
        when(socialIdPort.nextId()).thenReturn(20L);
        when(relationRepository.findRelation(1L, 2L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());

        FollowResultVO result = relationService.unfollow(1L, 2L);

        assertEquals("UNFOLLOWED", result.getStatus());
        verify(relationRepository).deleteRelation(1L, 2L, 1);
        verify(relationRepository, never()).deleteFollower(any(), any());
        verify(relationEventOutboxRepository).save(eq(20L), eq("FOLLOW"), any(String.class));
    }

    @Test
    void block_shouldEmitBlockEventAndNoDirectCounterSideEffects() {
        when(socialIdPort.nextId()).thenReturn(10L, 20L, 21L, 22L);
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
        verify(relationRepository, never()).deleteFollower(any(), any());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(relationEventOutboxRepository).save(eq(20L), eq("BLOCK"), payloadCaptor.capture());
        assertEquals("{\"eventId\":20,\"sourceId\":1,\"targetId\":2}", payloadCaptor.getValue());
        verify(relationEventOutboxRepository).save(eq(21L), eq("FOLLOW"), any(String.class));
        verify(relationEventOutboxRepository).save(eq(22L), eq("FOLLOW"), any(String.class));
    }
}
