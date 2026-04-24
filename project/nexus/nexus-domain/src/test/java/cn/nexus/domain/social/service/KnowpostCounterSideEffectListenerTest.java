package cn.nexus.domain.social.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.counter.model.event.CounterEvent;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.IFeedCounterSideEffectPort;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KnowpostCounterSideEffectListenerTest {

    @Test
    void onCounterChanged_shouldUpdateLikeReceivedAndFeedCacheForPostLikeDelta() {
        IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        IFeedCounterSideEffectPort feedCounterSideEffectPort = Mockito.mock(IFeedCounterSideEffectPort.class);
        KnowpostCounterSideEffectListener listener = new KnowpostCounterSideEffectListener(
                postAuthorPort,
                userCounterService,
                feedCounterSideEffectPort);

        when(postAuthorPort.getPostAuthorId(101L)).thenReturn(9L);

        listener.onCounterChanged(CounterEvent.builder()
                .requestId("rid-1")
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(101L)
                .counterType(ObjectCounterType.LIKE)
                .actorUserId(2L)
                .delta(1L)
                .tsMs(1000L)
                .build());

        verify(userCounterService).incrementLikesReceived(9L, 1L);
        verify(feedCounterSideEffectPort).applyPostLikeDelta(101L, 1L);
    }

    @Test
    void onCounterChanged_shouldIgnoreNonPostEvents() {
        IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        IFeedCounterSideEffectPort feedCounterSideEffectPort = Mockito.mock(IFeedCounterSideEffectPort.class);
        KnowpostCounterSideEffectListener listener = new KnowpostCounterSideEffectListener(
                postAuthorPort,
                userCounterService,
                feedCounterSideEffectPort);

        listener.onCounterChanged(CounterEvent.builder()
                .requestId("rid-2")
                .targetType(ReactionTargetTypeEnumVO.COMMENT)
                .targetId(201L)
                .counterType(ObjectCounterType.LIKE)
                .actorUserId(2L)
                .delta(1L)
                .tsMs(1000L)
                .build());

        verify(userCounterService, never()).incrementLikesReceived(Mockito.anyLong(), Mockito.anyLong());
        verify(feedCounterSideEffectPort, never()).applyPostLikeDelta(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    void onCounterChanged_shouldIgnoreZeroDelta() {
        IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        IFeedCounterSideEffectPort feedCounterSideEffectPort = Mockito.mock(IFeedCounterSideEffectPort.class);
        KnowpostCounterSideEffectListener listener = new KnowpostCounterSideEffectListener(
                postAuthorPort,
                userCounterService,
                feedCounterSideEffectPort);

        listener.onCounterChanged(CounterEvent.builder()
                .requestId("rid-3")
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(101L)
                .counterType(ObjectCounterType.LIKE)
                .actorUserId(2L)
                .delta(0L)
                .tsMs(1000L)
                .build());

        verify(userCounterService, never()).incrementLikesReceived(Mockito.anyLong(), Mockito.anyLong());
        verify(feedCounterSideEffectPort, never()).applyPostLikeDelta(Mockito.anyLong(), Mockito.anyLong());
    }
}
