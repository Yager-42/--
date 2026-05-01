package cn.nexus.trigger.mq.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.social.adapter.port.IInteractionCommentInboxPort;
import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CommentLikeChangedConsumerTest {

    @Test
    void onMessage_shouldReadObjectLikeBeforeRefreshingHotRank() {
        IInteractionCommentInboxPort inboxPort = Mockito.mock(IInteractionCommentInboxPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentHotRankRepository hotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        CommentLikeChangedConsumer consumer = new CommentLikeChangedConsumer(
                inboxPort,
                commentRepository,
                hotRankRepository,
                objectCounterService);

        CommentLikeChangedEvent event = new CommentLikeChangedEvent();
        event.setEventId("evt-like-1");
        event.setRootCommentId(101L);
        event.setPostId(88L);
        event.setDelta(1L);

        when(inboxPort.save("evt-like-1", "COMMENT_LIKE_CHANGED", null)).thenReturn(true);
        when(commentRepository.getBrief(101L)).thenReturn(CommentBriefVO.builder()
                .commentId(101L)
                .postId(88L)
                .status(1)
                .likeCount(5L)
                .build());

        consumer.onMessage(event);

        verify(objectCounterService, never()).getPostCounts(Mockito.anyLong(), any());
        verify(hotRankRepository).upsert(88L, 101L, 50D);
    }

    @Test
    void onMessage_shouldSkipWhenInboxRejectsDuplicate() {
        IInteractionCommentInboxPort inboxPort = Mockito.mock(IInteractionCommentInboxPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentHotRankRepository hotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        CommentLikeChangedConsumer consumer = new CommentLikeChangedConsumer(
                inboxPort,
                commentRepository,
                hotRankRepository,
                objectCounterService);

        CommentLikeChangedEvent event = new CommentLikeChangedEvent();
        event.setEventId("evt-like-dup");
        event.setRootCommentId(101L);
        event.setPostId(88L);
        event.setDelta(1L);

        when(inboxPort.save("evt-like-dup", "COMMENT_LIKE_CHANGED", null)).thenReturn(false);

        consumer.onMessage(event);

        verify(objectCounterService, never()).getPostCounts(Mockito.anyLong(), any());
        verify(hotRankRepository, never()).upsert(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyDouble());
    }

    @Test
    void onMessage_shouldBeIdempotentForSameEventId() {
        IInteractionCommentInboxPort inboxPort = Mockito.mock(IInteractionCommentInboxPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentHotRankRepository hotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        CommentLikeChangedConsumer consumer = new CommentLikeChangedConsumer(
                inboxPort,
                commentRepository,
                hotRankRepository,
                objectCounterService);

        CommentLikeChangedEvent event = new CommentLikeChangedEvent();
        event.setEventId("evt-like-idem");
        event.setRootCommentId(101L);
        event.setPostId(88L);
        event.setDelta(1L);

        when(inboxPort.save("evt-like-idem", "COMMENT_LIKE_CHANGED", null)).thenReturn(true, false);
        when(commentRepository.getBrief(101L)).thenReturn(CommentBriefVO.builder()
                .commentId(101L)
                .postId(88L)
                .status(1)
                .likeCount(5L)
                .build());

        consumer.onMessage(event);
        consumer.onMessage(event);

        verify(objectCounterService, never()).getPostCounts(Mockito.anyLong(), any());
        verify(hotRankRepository, Mockito.times(1)).upsert(88L, 101L, 50D);
    }
}
