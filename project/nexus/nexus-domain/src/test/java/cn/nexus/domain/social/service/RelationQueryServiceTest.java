package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.valobj.RelationListVO;
import cn.nexus.domain.social.model.valobj.RelationStateBatchVO;
import cn.nexus.domain.social.model.valobj.RelationUserEdgeVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RelationQueryServiceTest {

    private IUserBaseRepository userBaseRepository;
    private IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private IRelationRepository relationRepository;
    private RelationQueryService relationQueryService;

    @BeforeEach
    void setUp() {
        userBaseRepository = Mockito.mock(IUserBaseRepository.class);
        relationAdjacencyCachePort = Mockito.mock(IRelationAdjacencyCachePort.class);
        relationRepository = Mockito.mock(IRelationRepository.class);
        relationQueryService = new RelationQueryService(
                userBaseRepository,
                relationAdjacencyCachePort,
                relationRepository
        );
    }

    @Test
    void following_shouldClampLimitAndAssembleProfilesInEdgeOrder() {
        when(relationAdjacencyCachePort.pageFollowing(1L, "cursor-1", 50))
                .thenReturn(List.of(
                        RelationUserEdgeVO.builder().userId(2L).followTimeMs(100L).build(),
                        RelationUserEdgeVO.builder().userId(3L).followTimeMs(200L).build()
                ));
        when(userBaseRepository.listByUserIds(List.of(2L, 3L)))
                .thenReturn(List.of(
                        UserBriefVO.builder().userId(3L).nickname("bob").avatarUrl("b.png").build(),
                        UserBriefVO.builder().userId(2L).nickname("alice").avatarUrl("a.png").build()
                ));

        RelationListVO result = relationQueryService.following(1L, "cursor-1", 99);

        assertEquals(2, result.getItems().size());
        assertEquals(List.of(2L, 3L), result.getItems().stream().map(item -> item.getUserId()).toList());
        assertEquals(List.of("alice", "bob"), result.getItems().stream().map(item -> item.getNickname()).toList());
        assertEquals("200:3", result.getNextCursor());
        verify(relationAdjacencyCachePort).pageFollowing(1L, "cursor-1", 50);
    }

    @Test
    void followers_shouldReturnEmptyWhenNoEdges() {
        when(relationAdjacencyCachePort.pageFollowers(9L, null, 20)).thenReturn(List.of());

        RelationListVO result = relationQueryService.followers(9L, null, null);

        assertTrue(result.getItems().isEmpty());
        assertNull(result.getNextCursor());
    }

    @Test
    void batchState_shouldDeduplicateTargetsAndTreatBlockAsBidirectional() {
        when(relationRepository.batchFindActiveFollowTargets(1L, List.of(2L, 3L, 4L))).thenReturn(List.of(2L, 4L));
        when(relationRepository.batchFindBlockTargetsBySource(1L, List.of(2L, 3L, 4L))).thenReturn(List.of(3L));
        when(relationRepository.batchFindBlockSourcesByTarget(1L, List.of(2L, 3L, 4L))).thenReturn(List.of(4L));

        RelationStateBatchVO result = relationQueryService.batchState(1L, Arrays.asList(2L, 3L, 2L, null, 4L));

        assertEquals(List.of(2L, 4L), result.getFollowingUserIds());
        assertEquals(List.of(3L, 4L), result.getBlockedUserIds());
        verify(relationRepository).batchFindActiveFollowTargets(1L, List.of(2L, 3L, 4L));
        verify(relationRepository).batchFindBlockTargetsBySource(1L, List.of(2L, 3L, 4L));
        verify(relationRepository).batchFindBlockSourcesByTarget(1L, List.of(2L, 3L, 4L));
    }

    @Test
    void batchFollowing_shouldReturnFollowingIdsAsSet() {
        when(relationRepository.batchFindActiveFollowTargets(1L, List.of(2L, 3L))).thenReturn(List.of(3L));
        when(relationRepository.batchFindBlockTargetsBySource(1L, List.of(2L, 3L))).thenReturn(List.of());
        when(relationRepository.batchFindBlockSourcesByTarget(1L, List.of(2L, 3L))).thenReturn(List.of());

        Set<Long> result = relationQueryService.batchFollowing(1L, List.of(2L, 3L));

        assertEquals(Set.of(3L), result);
    }
}
