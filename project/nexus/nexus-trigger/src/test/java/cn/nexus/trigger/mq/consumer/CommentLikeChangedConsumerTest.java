package cn.nexus.trigger.mq.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.port.IObjectCounterPort;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.IInteractionCommentInboxPort;
import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommentLikeChangedConsumerTest {

    @Test
    void onMessage_shouldReadUnifiedCounterBeforeRefreshingHotRank() {
        IInteractionCommentInboxPort inboxPort = Mockito.mock(IInteractionCommentInboxPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentHotRankRepository hotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IObjectCounterPort objectCounterPort = Mockito.mock(IObjectCounterPort.class);
        CommentLikeChangedConsumer consumer = new CommentLikeChangedConsumer(
                inboxPort,
                commentRepository,
                hotRankRepository,
                objectCounterPort);

        CommentLikeChangedEvent event = new CommentLikeChangedEvent();
        event.setEventId("evt-like-1");
        event.setRootCommentId(101L);
        event.setPostId(88L);
        event.setDelta(1L);

        when(inboxPort.save("evt-like-1", "COMMENT_LIKE_CHANGED", null)).thenReturn(true);
        when(objectCounterPort.increment(target(101L, ObjectCounterType.LIKE), 1L)).thenReturn(6L);
        when(objectCounterPort.getCount(target(101L, ObjectCounterType.REPLY))).thenReturn(4L);
        when(commentRepository.getBrief(101L)).thenReturn(CommentBriefVO.builder()
                .commentId(101L)
                .postId(88L)
                .status(1)
                .build());

        consumer.onMessage(event);

        verify(objectCounterPort).increment(target(101L, ObjectCounterType.LIKE), 1L);
        verify(commentRepository).addLikeCount(101L, 1L);
        verify(objectCounterPort).getCount(target(101L, ObjectCounterType.REPLY));
        verify(hotRankRepository).upsert(88L, 101L, 140D);
    }

    @Test
    void onMessage_shouldSkipWhenInboxRejectsDuplicate() {
        IInteractionCommentInboxPort inboxPort = Mockito.mock(IInteractionCommentInboxPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentHotRankRepository hotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IObjectCounterPort objectCounterPort = Mockito.mock(IObjectCounterPort.class);
        CommentLikeChangedConsumer consumer = new CommentLikeChangedConsumer(
                inboxPort,
                commentRepository,
                hotRankRepository,
                objectCounterPort);

        CommentLikeChangedEvent event = new CommentLikeChangedEvent();
        event.setEventId("evt-like-dup");
        event.setRootCommentId(101L);
        event.setPostId(88L);
        event.setDelta(1L);

        when(inboxPort.save("evt-like-dup", "COMMENT_LIKE_CHANGED", null)).thenReturn(false);

        consumer.onMessage(event);

        verify(objectCounterPort, never()).increment(any(), Mockito.anyLong());
        verify(commentRepository, never()).addLikeCount(Mockito.anyLong(), Mockito.anyLong());
        verify(hotRankRepository, never()).upsert(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyDouble());
    }

    @Test
    void onMessage_shouldBeIdempotentForSameEventId() {
        IInteractionCommentInboxPort inboxPort = Mockito.mock(IInteractionCommentInboxPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentHotRankRepository hotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IObjectCounterPort objectCounterPort = Mockito.mock(IObjectCounterPort.class);
        CommentLikeChangedConsumer consumer = new CommentLikeChangedConsumer(
                inboxPort,
                commentRepository,
                hotRankRepository,
                objectCounterPort);

        CommentLikeChangedEvent event = new CommentLikeChangedEvent();
        event.setEventId("evt-like-idem");
        event.setRootCommentId(101L);
        event.setPostId(88L);
        event.setDelta(1L);

        when(inboxPort.save("evt-like-idem", "COMMENT_LIKE_CHANGED", null)).thenReturn(true, false);
        when(objectCounterPort.increment(target(101L, ObjectCounterType.LIKE), 1L)).thenReturn(6L);
        when(objectCounterPort.getCount(target(101L, ObjectCounterType.REPLY))).thenReturn(4L);
        when(commentRepository.getBrief(101L)).thenReturn(CommentBriefVO.builder()
                .commentId(101L)
                .postId(88L)
                .status(1)
                .build());

        consumer.onMessage(event);
        consumer.onMessage(event);

        verify(objectCounterPort, Mockito.times(1)).increment(target(101L, ObjectCounterType.LIKE), 1L);
        verify(commentRepository, Mockito.times(1)).addLikeCount(101L, 1L);
        verify(hotRankRepository, Mockito.times(1)).upsert(88L, 101L, 140D);
    }

    private ObjectCounterTarget target(Long commentId, ObjectCounterType counterType) {
        return ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.COMMENT)
                .targetId(commentId)
                .counterType(counterType)
                .build();
    }
}
