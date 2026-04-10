package cn.nexus.trigger.job.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.infrastructure.adapter.social.repository.ReactionEventLogRepository;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionEventLogPO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ReactionRedisRecoveryRunnerTest {

    @Test
    void recoverFamily_shouldAdvanceCheckpointAfterSuccessfulBatch() {
        ReactionEventLogRepository repository = Mockito.mock(ReactionEventLogRepository.class);
        IReactionCachePort cachePort = Mockito.mock(IReactionCachePort.class);
        when(repository.pageAfterSeq("POST", "LIKE", 0L, 2)).thenReturn(List.of(
                event(11L, "evt-1", "POST", 101L, 7L, 1, 1),
                event(12L, "evt-2", "POST", 101L, 8L, 0, -1)
        )).thenReturn(List.of());
        when(cachePort.applyRecoveryEvent(eq(7L), any(), eq(1))).thenReturn(true);
        when(cachePort.applyRecoveryEvent(eq(8L), any(), eq(0))).thenReturn(true);
        when(cachePort.getRecoveryCheckpoint("POST", "LIKE")).thenReturn(0L);

        ReactionRedisRecoveryRunner runner = new ReactionRedisRecoveryRunner(repository, cachePort, 2);

        boolean success = runner.recoverFamily("POST", "LIKE");

        assertTrue(success);
        verify(cachePort).setRecoveryCheckpoint("POST", "LIKE", 12L);
    }

    @Test
    void recoverFamily_shouldKeepCheckpointWhenPageFailsAfterPartialApply() {
        ReactionEventLogRepository repository = Mockito.mock(ReactionEventLogRepository.class);
        IReactionCachePort cachePort = Mockito.mock(IReactionCachePort.class);
        when(repository.pageAfterSeq("POST", "LIKE", 5L, 2)).thenReturn(List.of(
                event(11L, "evt-1", "POST", 101L, 7L, 1, 1),
                event(12L, "evt-2", "POST", 101L, 8L, 0, -1)
        ));
        when(cachePort.getRecoveryCheckpoint("POST", "LIKE")).thenReturn(5L);
        when(cachePort.applyRecoveryEvent(eq(7L), any(), eq(1))).thenReturn(true);
        when(cachePort.applyRecoveryEvent(eq(8L), any(), eq(0))).thenReturn(false);

        ReactionRedisRecoveryRunner runner = new ReactionRedisRecoveryRunner(repository, cachePort, 2);

        boolean success = runner.recoverFamily("POST", "LIKE");

        assertFalse(success);
        verify(cachePort, never()).setRecoveryCheckpoint(any(), any(), any(Long.class));
    }

    @Test
    void recoverFamily_shouldReplayFromPreviousCheckpointOnRetry() {
        ReactionEventLogRepository repository = Mockito.mock(ReactionEventLogRepository.class);
        IReactionCachePort cachePort = Mockito.mock(IReactionCachePort.class);
        when(cachePort.getRecoveryCheckpoint("COMMENT", "LIKE")).thenReturn(20L, 20L);
        when(repository.pageAfterSeq("COMMENT", "LIKE", 20L, 2)).thenReturn(List.of(
                event(21L, "evt-21", "COMMENT", 501L, 9L, 1, 1),
                event(22L, "evt-22", "COMMENT", 501L, 10L, 0, -1)
        ), List.of(
                event(21L, "evt-21", "COMMENT", 501L, 9L, 1, 1),
                event(22L, "evt-22", "COMMENT", 501L, 10L, 0, -1)
        ), List.of());
        when(cachePort.applyRecoveryEvent(eq(9L), any(), eq(1))).thenReturn(true, true);
        when(cachePort.applyRecoveryEvent(eq(10L), any(), eq(0))).thenReturn(false, true);

        ReactionRedisRecoveryRunner runner = new ReactionRedisRecoveryRunner(repository, cachePort, 2);

        assertFalse(runner.recoverFamily("COMMENT", "LIKE"));
        assertTrue(runner.recoverFamily("COMMENT", "LIKE"));
        verify(cachePort).setRecoveryCheckpoint("COMMENT", "LIKE", 22L);
    }

    @Test
    void recoverAll_shouldKeepSeparateCheckpointsPerFamily() {
        ReactionEventLogRepository repository = Mockito.mock(ReactionEventLogRepository.class);
        IReactionCachePort cachePort = Mockito.mock(IReactionCachePort.class);
        when(cachePort.getRecoveryCheckpoint("POST", "LIKE")).thenReturn(0L);
        when(cachePort.getRecoveryCheckpoint("COMMENT", "LIKE")).thenReturn(0L);
        when(repository.pageAfterSeq("POST", "LIKE", 0L, 100)).thenReturn(List.of(event(31L, "evt-31", "POST", 701L, 5L, 1, 1))).thenReturn(List.of());
        when(repository.pageAfterSeq("COMMENT", "LIKE", 0L, 100)).thenReturn(List.of(event(41L, "evt-41", "COMMENT", 801L, 6L, 1, 1))).thenReturn(List.of());
        when(cachePort.applyRecoveryEvent(eq(5L), any(), eq(1))).thenReturn(true);
        when(cachePort.applyRecoveryEvent(eq(6L), any(), eq(1))).thenReturn(true);

        ReactionRedisRecoveryRunner runner = new ReactionRedisRecoveryRunner(repository, cachePort, 100);

        runner.recoverAll();

        verify(cachePort).setRecoveryCheckpoint("POST", "LIKE", 31L);
        verify(cachePort).setRecoveryCheckpoint("COMMENT", "LIKE", 41L);
    }

    @Test
    void recoverFamily_shouldMapTargetCorrectly() {
        ReactionEventLogRepository repository = Mockito.mock(ReactionEventLogRepository.class);
        IReactionCachePort cachePort = Mockito.mock(IReactionCachePort.class);
        when(cachePort.getRecoveryCheckpoint("POST", "LIKE")).thenReturn(0L);
        when(repository.pageAfterSeq("POST", "LIKE", 0L, 1)).thenReturn(List.of(event(51L, "evt-51", "POST", 901L, 66L, 1, 1))).thenReturn(List.of());
        when(cachePort.applyRecoveryEvent(eq(66L), any(), eq(1))).thenReturn(true);

        ReactionRedisRecoveryRunner runner = new ReactionRedisRecoveryRunner(repository, cachePort, 1);
        runner.recoverFamily("POST", "LIKE");

        ArgumentCaptor<ReactionTargetVO> captor = ArgumentCaptor.forClass(ReactionTargetVO.class);
        verify(cachePort).applyRecoveryEvent(eq(66L), captor.capture(), eq(1));
        assertEquals(ReactionTargetTypeEnumVO.POST, captor.getValue().getTargetType());
        assertEquals(ReactionTypeEnumVO.LIKE, captor.getValue().getReactionType());
        assertEquals(901L, captor.getValue().getTargetId());
    }

    private InteractionReactionEventLogPO event(long seq,
                                                String eventId,
                                                String targetType,
                                                long targetId,
                                                long userId,
                                                int desiredState,
                                                int delta) {
        InteractionReactionEventLogPO po = new InteractionReactionEventLogPO();
        po.setSeq(seq);
        po.setEventId(eventId);
        po.setTargetType(targetType);
        po.setTargetId(targetId);
        po.setReactionType("LIKE");
        po.setUserId(userId);
        po.setDesiredState(desiredState);
        po.setDelta(delta);
        po.setEventTime(1000L + seq);
        return po;
    }
}
