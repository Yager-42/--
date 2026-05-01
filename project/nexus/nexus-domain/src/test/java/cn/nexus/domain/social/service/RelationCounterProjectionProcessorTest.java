package cn.nexus.domain.social.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RelationCounterProjectionProcessorTest {

    private IRelationRepository relationRepository;
    private IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private IUserCounterService userCounterService;
    private RelationCounterProjectionProcessor processor;

    @BeforeEach
    void setUp() {
        relationRepository = Mockito.mock(IRelationRepository.class);
        relationAdjacencyCachePort = Mockito.mock(IRelationAdjacencyCachePort.class);
        userCounterService = Mockito.mock(IUserCounterService.class);
        processor = new RelationCounterProjectionProcessor(
                relationRepository,
                relationAdjacencyCachePort,
                userCounterService
        );
    }

    @Test
    void followActive_newEdge_shouldIncrementCounters() {
        RelationCounterProjectEvent event = followEvent(100L, 1L, 2L, "ACTIVE");
        when(relationRepository.findRelation(1L, 2L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        when(relationRepository.saveFollowerIfAbsent(eq(100L), eq(2L), eq(1L), any()))
                .thenReturn(true);

        processor.process(event);

        verify(userCounterService).incrementFollowings(1L, 1L);
        verify(userCounterService).incrementFollowers(2L, 1L);
        verify(relationAdjacencyCachePort).addFollowWithTtl(eq(1L), eq(2L), any(Long.class), eq(7200L));
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void unfollowActive_removedEdge_shouldDecrementCounters() {
        RelationCounterProjectEvent event = followEvent(101L, 1L, 2L, "UNFOLLOW");
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(null);
        when(relationRepository.deleteFollowerIfPresent(2L, 1L)).thenReturn(true);

        processor.process(event);

        verify(userCounterService).incrementFollowings(1L, -1L);
        verify(userCounterService).incrementFollowers(2L, -1L);
        verify(relationAdjacencyCachePort).removeFollowWithTtl(1L, 2L, 7200L);
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void followActive_duplicate_shouldNotIncrementCounters() {
        RelationCounterProjectEvent event = followEvent(102L, 1L, 2L, "ACTIVE");
        when(relationRepository.findRelation(1L, 2L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        when(relationRepository.saveFollowerIfAbsent(eq(102L), eq(2L), eq(1L), any()))
                .thenReturn(false);

        processor.process(event);

        verify(relationAdjacencyCachePort).addFollowWithTtl(eq(1L), eq(2L), any(Long.class), eq(7200L));
        verify(userCounterService, never()).incrementFollowings(any(), any(Long.class));
        verify(userCounterService, never()).incrementFollowers(any(), any(Long.class));
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void unfollow_duplicate_shouldNotDecrementCounters() {
        RelationCounterProjectEvent event = followEvent(103L, 1L, 2L, "UNFOLLOW");
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(null);
        when(relationRepository.deleteFollowerIfPresent(2L, 1L)).thenReturn(false);

        processor.process(event);

        verify(relationAdjacencyCachePort).removeFollowWithTtl(1L, 2L, 7200L);
        verify(userCounterService, never()).incrementFollowings(any(), any(Long.class));
        verify(userCounterService, never()).incrementFollowers(any(), any(Long.class));
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void postEvents_shouldBeIgnoredBecauseContentServiceOwnsPostCounterEdges() {
        RelationCounterProjectEvent event = postEvent(200L, 1L, 100L, "PUBLISHED");

        processor.process(event);

        verify(userCounterService, never()).incrementPosts(any(), any(Long.class));
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void process_block_shouldOnlyRemoveAdjacencyWhenFollowerProjectionTransitions() {
        RelationCounterProjectEvent event = blockEvent(300L, 11L, 22L);
        when(relationRepository.deleteFollowerIfPresent(22L, 11L)).thenReturn(false);
        when(relationRepository.deleteFollowerIfPresent(11L, 22L)).thenReturn(true);

        processor.process(event);

        verify(relationAdjacencyCachePort, never()).removeFollowWithTtl(11L, 22L, 7200L);
        verify(relationAdjacencyCachePort).removeFollowWithTtl(22L, 11L, 7200L);
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    private RelationCounterProjectEvent followEvent(Long relationEventId, Long sourceId, Long targetId, String status) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + relationEventId);
        event.setRelationEventId(relationEventId);
        event.setEventType("FOLLOW");
        event.setSourceId(sourceId);
        event.setTargetId(targetId);
        event.setStatus(status);
        return event;
    }

    private RelationCounterProjectEvent blockEvent(Long relationEventId, Long sourceId, Long targetId) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + relationEventId);
        event.setRelationEventId(relationEventId);
        event.setEventType("BLOCK");
        event.setSourceId(sourceId);
        event.setTargetId(targetId);
        event.setStatus("BLOCKED");
        return event;
    }

    private RelationCounterProjectEvent postEvent(Long relationEventId, Long authorId, Long postId, String status) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + relationEventId);
        event.setRelationEventId(relationEventId);
        event.setEventType("POST");
        event.setSourceId(authorId);
        event.setTargetId(postId);
        event.setStatus(status);
        return event;
    }
}
