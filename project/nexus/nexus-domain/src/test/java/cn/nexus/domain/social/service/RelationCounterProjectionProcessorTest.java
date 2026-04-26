package cn.nexus.domain.social.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IClass2CounterProjectionStateRepository;
import cn.nexus.domain.social.adapter.repository.IClass2UserCounterRepairTaskRepository;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.valobj.Class2ProjectionAdvanceResult;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RelationCounterProjectionProcessorTest {

    private IRelationRepository relationRepository;
    private IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private IUserCounterService userCounterService;
    private IClass2CounterProjectionStateRepository projectionStateRepository;
    private IClass2UserCounterRepairTaskRepository repairTaskRepository;
    private IContentRepository contentRepository;
    private RelationCounterProjectionProcessor processor;

    @BeforeEach
    void setUp() {
        relationRepository = Mockito.mock(IRelationRepository.class);
        relationAdjacencyCachePort = Mockito.mock(IRelationAdjacencyCachePort.class);
        userCounterService = Mockito.mock(IUserCounterService.class);
        projectionStateRepository = Mockito.mock(IClass2CounterProjectionStateRepository.class);
        repairTaskRepository = Mockito.mock(IClass2UserCounterRepairTaskRepository.class);
        contentRepository = Mockito.mock(IContentRepository.class);
        processor = new RelationCounterProjectionProcessor(
                relationRepository,
                relationAdjacencyCachePort,
                userCounterService,
                projectionStateRepository,
                repairTaskRepository,
                contentRepository
        );
        when(projectionStateRepository.advanceIfNewer(any(), any(), anyLong()))
                .thenReturn(Class2ProjectionAdvanceResult.ADVANCED);
    }

    @Test
    void process_followActive_shouldReplayCacheAndRebuildCountersWhenFollowerRowAlreadyExists() {
        RelationCounterProjectEvent event = followEvent(100L, 1L, 2L, "ACTIVE");
        when(relationRepository.findRelation(1L, 2L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        when(relationRepository.saveFollowerIfAbsent(eq(100L), eq(2L), eq(1L), any()))
                .thenReturn(false);

        processor.process(event);

        verify(relationAdjacencyCachePort).addFollowWithTtl(eq(1L), eq(2L), any(Long.class), eq(7200L));
        verify(repairTaskRepository).enqueue("USER_CLASS2", 1L,
                "follow active missing follower transition", "USER_CLASS2:1");
        verify(repairTaskRepository).enqueue("USER_CLASS2", 2L,
                "follow active missing follower transition", "USER_CLASS2:2");
        verify(userCounterService, never()).applyClass2DeltaOnce(any(), any(), any(), anyLong());
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void process_followActive_shouldUpdateAdjacencyWithTwoHourTtlWhenTransitionHappens() {
        RelationCounterProjectEvent event = followEvent(101L, 1L, 2L, "ACTIVE");
        when(relationRepository.findRelation(1L, 2L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        when(relationRepository.saveFollowerIfAbsent(eq(101L), eq(2L), eq(1L), any()))
                .thenReturn(true);

        processor.process(event);

        verify(relationAdjacencyCachePort).addFollowWithTtl(eq(1L), eq(2L), any(Long.class), eq(7200L));
        verify(userCounterService).applyClass2DeltaOnce("relation-counter:101", 1L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING, 1L);
        verify(userCounterService).applyClass2DeltaOnce("relation-counter:101", 2L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWER, 1L);
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void process_followActive_whenProjectionStateIsStale_shouldNotMutateCountersOrFollowerProjection() {
        RelationCounterProjectEvent event = followEvent(106L, 1L, 2L, "ACTIVE");
        when(projectionStateRepository.advanceIfNewer("follow:1:2", "FOLLOW", 106L))
                .thenReturn(Class2ProjectionAdvanceResult.STALE);

        processor.process(event);

        verify(relationRepository, never()).saveFollowerIfAbsent(anyLong(), anyLong(), anyLong(), any());
        verify(userCounterService, never()).applyClass2DeltaOnce(any(), any(), any(), anyLong());
        verify(relationAdjacencyCachePort, never()).addFollowWithTtl(anyLong(), anyLong(), anyLong(), anyLong());
        verify(repairTaskRepository, never()).enqueue("USER_CLASS2", 1L, "follow active ordering uncertainty", "USER_CLASS2:1");
        verify(repairTaskRepository, never()).enqueue("USER_CLASS2", 2L, "follow active ordering uncertainty", "USER_CLASS2:2");
    }

    @Test
    void process_followActive_missingOrderingMetadata_shouldEnqueueRepairAndSkipIncrementalProjection() {
        RelationCounterProjectEvent event = followEvent(105L, 1L, 2L, "ACTIVE");
        event.setProjectionKey(null);
        event.setProjectionVersion(null);

        processor.process(event);

        verify(repairTaskRepository).enqueue("USER_CLASS2", 1L,
                "projection ordering metadata missing", "USER_CLASS2:1");
        verify(repairTaskRepository).enqueue("USER_CLASS2", 2L,
                "projection ordering metadata missing", "USER_CLASS2:2");
        verify(projectionStateRepository, never()).advanceIfNewer(any(), any(), anyLong());
        verify(userCounterService, never()).applyClass2DeltaOnce(any(), any(), any(), anyLong());
        verify(relationRepository, never()).saveFollowerIfAbsent(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void process_followUnfollow_shouldReplayCacheRemovalAndRebuildCountersWhenFollowerRowAlreadyDeleted() {
        RelationCounterProjectEvent event = followEvent(102L, 1L, 2L, "UNFOLLOW");
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(null);
        when(relationRepository.deleteFollowerIfPresent(2L, 1L)).thenReturn(false);

        processor.process(event);

        verify(relationAdjacencyCachePort).removeFollowWithTtl(1L, 2L, 7200L);
        verify(repairTaskRepository).enqueue("USER_CLASS2", 1L,
                "follow unfollow missing follower transition", "USER_CLASS2:1");
        verify(repairTaskRepository).enqueue("USER_CLASS2", 2L,
                "follow unfollow missing follower transition", "USER_CLASS2:2");
        verify(userCounterService, never()).applyClass2DeltaOnce(any(), any(), any(), anyLong());
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void process_followUnfollow_shouldUpdateAdjacencyWithTwoHourTtlWhenTransitionHappens() {
        RelationCounterProjectEvent event = followEvent(103L, 1L, 2L, "UNFOLLOW");
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(null);
        when(relationRepository.deleteFollowerIfPresent(2L, 1L)).thenReturn(true);

        processor.process(event);

        verify(relationAdjacencyCachePort).removeFollowWithTtl(1L, 2L, 7200L);
        verify(userCounterService).applyClass2DeltaOnce("relation-counter:103", 1L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING, -1L);
        verify(userCounterService).applyClass2DeltaOnce("relation-counter:103", 2L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWER, -1L);
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void process_block_shouldOnlyRemoveAdjacencyWhenFollowerProjectionTransitions() {
        RelationCounterProjectEvent event = blockEvent(200L, 11L, 22L);

        processor.process(event);

        verify(relationAdjacencyCachePort).removeFollowWithTtl(11L, 22L, 7200L);
        verify(relationAdjacencyCachePort).removeFollowWithTtl(22L, 11L, 7200L);
        verify(relationRepository, never()).deleteFollowerIfPresent(any(), any());
    }

    @Test
    void process_blockThenUnfollow_shouldStillApplySingleUnfollowDelta() {
        RelationCounterProjectEvent block = blockEvent(201L, 31L, 32L);
        RelationCounterProjectEvent unfollow = followEvent(202L, 31L, 32L, "UNFOLLOW");
        when(relationRepository.findRelation(31L, 32L, 1)).thenReturn(null);
        when(relationRepository.deleteFollowerIfPresent(32L, 31L)).thenReturn(true);

        processor.process(block);
        processor.process(unfollow);

        verify(relationAdjacencyCachePort, Mockito.times(2)).removeFollowWithTtl(31L, 32L, 7200L);
        verify(relationAdjacencyCachePort).removeFollowWithTtl(32L, 31L, 7200L);
        verify(userCounterService).applyClass2DeltaOnce("relation-counter:202", 31L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING, -1L);
        verify(userCounterService).applyClass2DeltaOnce("relation-counter:202", 32L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWER, -1L);
        verify(repairTaskRepository, never()).enqueue("USER_CLASS2", 31L,
                "follow unfollow ordering uncertainty", "USER_CLASS2:31");
        verify(repairTaskRepository, never()).enqueue("USER_CLASS2", 32L,
                "follow unfollow ordering uncertainty", "USER_CLASS2:32");
    }

    @Test
    void process_followActive_shouldEnqueueRepairWhenAdjacencyCacheSideEffectFails() {
        RelationCounterProjectEvent event = followEvent(104L, 3L, 4L, "ACTIVE");
        when(relationRepository.findRelation(3L, 4L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        when(relationRepository.saveFollowerIfAbsent(eq(104L), eq(4L), eq(3L), any()))
                .thenReturn(true);
        Mockito.doThrow(new IllegalStateException("cache down"))
                .when(relationAdjacencyCachePort)
                .addFollowWithTtl(eq(3L), eq(4L), any(Long.class), eq(7200L));

        processor.process(event);

        verify(repairTaskRepository).enqueue("USER_CLASS2", 3L,
                "follow projection side effect failed", "USER_CLASS2:3");
        verify(repairTaskRepository).enqueue("USER_CLASS2", 4L,
                "follow projection side effect failed", "USER_CLASS2:4");
    }

    @Test
    void process_followActive_replayAfterRollback_shouldRemainIdempotentByDeltaDedup() {
        RelationCounterProjectEvent event = followEvent(250L, 7L, 8L, "ACTIVE");
        when(relationRepository.findRelation(7L, 8L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        when(relationRepository.saveFollowerIfAbsent(eq(250L), eq(8L), eq(7L), any()))
                .thenReturn(true);
        when(projectionStateRepository.advanceIfNewer("follow:7:8", "FOLLOW", 250L))
                .thenReturn(Class2ProjectionAdvanceResult.ADVANCED, Class2ProjectionAdvanceResult.ADVANCED);
        when(userCounterService.applyClass2DeltaOnce(
                "relation-counter:250",
                7L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING,
                1L))
                .thenReturn(true, false);
        when(userCounterService.applyClass2DeltaOnce(
                "relation-counter:250",
                8L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWER,
                1L))
                .thenReturn(true, false);

        processor.process(event);
        processor.process(event);

        verify(userCounterService, Mockito.times(2)).applyClass2DeltaOnce(
                "relation-counter:250",
                7L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWING,
                1L);
        verify(userCounterService, Mockito.times(2)).applyClass2DeltaOnce(
                "relation-counter:250",
                8L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.FOLLOWER,
                1L);
        verify(repairTaskRepository, never()).enqueue("USER_CLASS2", 7L,
                "follow active missing follower transition", "USER_CLASS2:7");
        verify(repairTaskRepository, never()).enqueue("USER_CLASS2", 8L,
                "follow active missing follower transition", "USER_CLASS2:8");
    }

    @Test
    void process_postPublished_shouldRebuildAuthorPostCounterFromTruth() {
        RelationCounterProjectEvent event = postEvent(300L, 11L, 101L, "PUBLISHED");
        when(contentRepository.findPostBypassCache(101L))
                .thenReturn(ContentPostEntity.builder().postId(101L).status(2).build());

        processor.process(event);

        verify(userCounterService).applyClass2DeltaOnce("relation-counter:300", 11L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.POST, 1L);
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void process_postPublished_shouldNotRequireTargetId() {
        RelationCounterProjectEvent event = postEvent(302L, 11L, null, "PUBLISHED");
        when(contentRepository.findPostBypassCache(null))
                .thenReturn(null);

        processor.process(event);

        verify(userCounterService, never()).applyClass2DeltaOnce(any(), any(), any(), anyLong());
        verify(repairTaskRepository).enqueue("USER_CLASS2", 11L,
                "projection ordering metadata missing", "USER_CLASS2:11");
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void process_postPublished_missingOrderingMetadata_shouldEnqueueRepairAndSkipIncrementalProjection() {
        RelationCounterProjectEvent event = postEvent(303L, 11L, 101L, "PUBLISHED");
        event.setProjectionKey("  ");
        event.setProjectionVersion(null);

        processor.process(event);

        verify(repairTaskRepository).enqueue("USER_CLASS2", 11L,
                "projection ordering metadata missing", "USER_CLASS2:11");
        verify(projectionStateRepository, never()).advanceIfNewer(any(), any(), anyLong());
        verify(userCounterService, never()).applyClass2DeltaOnce(any(), any(), any(), anyLong());
        verify(contentRepository, never()).findPostBypassCache(any());
    }

    @Test
    void process_postUnpublished_shouldRebuildAuthorPostCounterFromTruth() {
        RelationCounterProjectEvent event = postEvent(301L, 11L, 101L, "UNPUBLISHED");
        when(contentRepository.findPostBypassCache(101L))
                .thenReturn(null);

        processor.process(event);

        verify(userCounterService).applyClass2DeltaOnce("relation-counter:301", 11L,
                cn.nexus.domain.counter.model.valobj.UserCounterType.POST, -1L);
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
        event.setProjectionKey("follow:" + sourceId + ":" + targetId);
        event.setProjectionVersion(relationEventId);
        return event;
    }

    private RelationCounterProjectEvent blockEvent(Long relationEventId, Long sourceId, Long targetId) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + relationEventId);
        event.setRelationEventId(relationEventId);
        event.setEventType("BLOCK");
        event.setSourceId(sourceId);
        event.setTargetId(targetId);
        event.setProjectionKey("block:" + sourceId + ":" + targetId);
        event.setProjectionVersion(relationEventId);
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
        event.setProjectionKey(postId == null ? null : "post:" + postId);
        event.setProjectionVersion(relationEventId);
        return event;
    }
}
