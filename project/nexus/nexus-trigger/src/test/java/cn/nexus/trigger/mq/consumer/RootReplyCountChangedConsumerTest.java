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
import cn.nexus.types.event.interaction.RootReplyCountChangedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RootReplyCountChangedConsumerTest {

    @Test
    void onMessage_shouldIncrementUnifiedCounterBeforeRefreshingHotRank() {
        IInteractionCommentInboxPort inboxPort = Mockito.mock(IInteractionCommentInboxPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentHotRankRepository hotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IObjectCounterPort objectCounterPort = Mockito.mock(IObjectCounterPort.class);
        RootReplyCountChangedConsumer consumer = new RootReplyCountChangedConsumer(
                inboxPort,
                commentRepository,
                hotRankRepository,
                objectCounterPort);

        RootReplyCountChangedEvent event = new RootReplyCountChangedEvent();
        event.setEventId("evt-reply-1");
        event.setRootCommentId(202L);
        event.setPostId(99L);
        event.setDelta(1L);

        when(inboxPort.save("evt-reply-1", "ROOT_REPLY_COUNT_CHANGED", null)).thenReturn(true);
        when(objectCounterPort.increment(target(202L, ObjectCounterType.REPLY), 1L)).thenReturn(5L);
        when(objectCounterPort.getCount(target(202L, ObjectCounterType.LIKE))).thenReturn(3L);
        when(commentRepository.getBrief(202L)).thenReturn(CommentBriefVO.builder()
                .commentId(202L)
                .postId(99L)
                .status(1)
                .build());

        consumer.onMessage(event);

        verify(objectCounterPort).increment(target(202L, ObjectCounterType.REPLY), 1L);
        verify(commentRepository).addReplyCount(202L, 1L);
        verify(objectCounterPort).getCount(target(202L, ObjectCounterType.LIKE));
        verify(hotRankRepository).upsert(99L, 202L, 130D);
    }

    @Test
    void onMessage_shouldSkipWhenInboxRejectsDuplicate() {
        IInteractionCommentInboxPort inboxPort = Mockito.mock(IInteractionCommentInboxPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentHotRankRepository hotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IObjectCounterPort objectCounterPort = Mockito.mock(IObjectCounterPort.class);
        RootReplyCountChangedConsumer consumer = new RootReplyCountChangedConsumer(
                inboxPort,
                commentRepository,
                hotRankRepository,
                objectCounterPort);

        RootReplyCountChangedEvent event = new RootReplyCountChangedEvent();
        event.setEventId("evt-reply-dup");
        event.setRootCommentId(202L);
        event.setPostId(99L);
        event.setDelta(1L);

        when(inboxPort.save("evt-reply-dup", "ROOT_REPLY_COUNT_CHANGED", null)).thenReturn(false);

        consumer.onMessage(event);

        verify(objectCounterPort, never()).increment(any(), Mockito.anyLong());
        verify(commentRepository, never()).addReplyCount(Mockito.anyLong(), Mockito.anyLong());
        verify(hotRankRepository, never()).upsert(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyDouble());
    }

    private ObjectCounterTarget target(Long commentId, ObjectCounterType counterType) {
        return ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.COMMENT)
                .targetId(commentId)
                .counterType(counterType)
                .build();
    }
}
