package cn.nexus.domain.social.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.port.IUserCounterPort;
import cn.nexus.domain.counter.model.event.CounterEvent;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.port.IFeedCounterSideEffectPort;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IReactionLikeUnlikeMqPort;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KnowpostCounterSideEffectListenerTest {

    @Test
    void onCounterChanged_shouldUpdateLikeReceivedAndFeedCacheForPostLikeDelta() {
        IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        IUserCounterPort userCounterPort = Mockito.mock(IUserCounterPort.class);
        IFeedCounterSideEffectPort feedCounterSideEffectPort = Mockito.mock(IFeedCounterSideEffectPort.class);
        IReactionLikeUnlikeMqPort reactionLikeUnlikeMqPort = Mockito.mock(IReactionLikeUnlikeMqPort.class);
        KnowpostCounterSideEffectListener listener = new KnowpostCounterSideEffectListener(
                postAuthorPort,
                userCounterPort,
                feedCounterSideEffectPort,
                reactionLikeUnlikeMqPort);

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

        verify(userCounterPort).increment(9L, UserCounterType.LIKE_RECEIVED, 1L);
        verify(feedCounterSideEffectPort).applyPostLikeDelta(101L, 1L);
        verify(reactionLikeUnlikeMqPort).publishLike(Mockito.argThat((LikeUnlikePostEvent e) ->
                e != null
                        && "rid-1".equals(e.getEventId())
                        && Long.valueOf(2L).equals(e.getUserId())
                        && Long.valueOf(101L).equals(e.getPostId())
                        && Long.valueOf(9L).equals(e.getPostCreatorId())
                        && Integer.valueOf(1).equals(e.getType())
                        && Long.valueOf(1000L).equals(e.getCreateTime())));
    }

    @Test
    void onCounterChanged_shouldIgnoreNonPostEvents() {
        IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        IUserCounterPort userCounterPort = Mockito.mock(IUserCounterPort.class);
        IFeedCounterSideEffectPort feedCounterSideEffectPort = Mockito.mock(IFeedCounterSideEffectPort.class);
        IReactionLikeUnlikeMqPort reactionLikeUnlikeMqPort = Mockito.mock(IReactionLikeUnlikeMqPort.class);
        KnowpostCounterSideEffectListener listener = new KnowpostCounterSideEffectListener(
                postAuthorPort,
                userCounterPort,
                feedCounterSideEffectPort,
                reactionLikeUnlikeMqPort);

        listener.onCounterChanged(CounterEvent.builder()
                .requestId("rid-2")
                .targetType(ReactionTargetTypeEnumVO.COMMENT)
                .targetId(201L)
                .counterType(ObjectCounterType.LIKE)
                .actorUserId(2L)
                .delta(1L)
                .tsMs(1000L)
                .build());

        verify(userCounterPort, never()).increment(Mockito.anyLong(), Mockito.any(), Mockito.anyLong());
        verify(feedCounterSideEffectPort, never()).applyPostLikeDelta(Mockito.anyLong(), Mockito.anyLong());
        verify(reactionLikeUnlikeMqPort, never()).publishLike(Mockito.any());
        verify(reactionLikeUnlikeMqPort, never()).publishUnlike(Mockito.any());
    }

    @Test
    void onCounterChanged_shouldIgnoreZeroDelta() {
        IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        IUserCounterPort userCounterPort = Mockito.mock(IUserCounterPort.class);
        IFeedCounterSideEffectPort feedCounterSideEffectPort = Mockito.mock(IFeedCounterSideEffectPort.class);
        IReactionLikeUnlikeMqPort reactionLikeUnlikeMqPort = Mockito.mock(IReactionLikeUnlikeMqPort.class);
        KnowpostCounterSideEffectListener listener = new KnowpostCounterSideEffectListener(
                postAuthorPort,
                userCounterPort,
                feedCounterSideEffectPort,
                reactionLikeUnlikeMqPort);

        listener.onCounterChanged(CounterEvent.builder()
                .requestId("rid-3")
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(101L)
                .counterType(ObjectCounterType.LIKE)
                .actorUserId(2L)
                .delta(0L)
                .tsMs(1000L)
                .build());

        verify(userCounterPort, never()).increment(Mockito.anyLong(), Mockito.any(), Mockito.anyLong());
        verify(feedCounterSideEffectPort, never()).applyPostLikeDelta(Mockito.anyLong(), Mockito.anyLong());
        verify(reactionLikeUnlikeMqPort, never()).publishLike(Mockito.any());
        verify(reactionLikeUnlikeMqPort, never()).publishUnlike(Mockito.any());
    }

    @Test
    void onCounterChanged_shouldPublishUnlikeForNegativeDelta() {
        IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        IUserCounterPort userCounterPort = Mockito.mock(IUserCounterPort.class);
        IFeedCounterSideEffectPort feedCounterSideEffectPort = Mockito.mock(IFeedCounterSideEffectPort.class);
        IReactionLikeUnlikeMqPort reactionLikeUnlikeMqPort = Mockito.mock(IReactionLikeUnlikeMqPort.class);
        KnowpostCounterSideEffectListener listener = new KnowpostCounterSideEffectListener(
                postAuthorPort,
                userCounterPort,
                feedCounterSideEffectPort,
                reactionLikeUnlikeMqPort);

        when(postAuthorPort.getPostAuthorId(101L)).thenReturn(9L);

        listener.onCounterChanged(CounterEvent.builder()
                .requestId("rid-4")
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(101L)
                .counterType(ObjectCounterType.LIKE)
                .actorUserId(2L)
                .delta(-1L)
                .tsMs(1000L)
                .build());

        verify(reactionLikeUnlikeMqPort).publishUnlike(Mockito.argThat((LikeUnlikePostEvent e) ->
                e != null
                        && "rid-4".equals(e.getEventId())
                        && Long.valueOf(2L).equals(e.getUserId())
                        && Long.valueOf(101L).equals(e.getPostId())
                        && Long.valueOf(9L).equals(e.getPostCreatorId())
                        && Integer.valueOf(0).equals(e.getType())
                        && Long.valueOf(1000L).equals(e.getCreateTime())));
    }
}
