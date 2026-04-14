package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.port.IUserCounterPort;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.adapter.port.IReactionCommentLikeChangedMqPort;
import cn.nexus.domain.social.adapter.port.IReactionEventLogMqPort;
import cn.nexus.domain.social.adapter.port.IReactionLikeUnlikeMqPort;
import cn.nexus.domain.social.adapter.port.IReactionNotifyMqPort;
import cn.nexus.domain.social.adapter.port.IReactionRecommendFeedbackMqPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.ReactionActionEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionEventLogRecordVO;
import cn.nexus.domain.social.model.valobj.ReactionResultVO;
import cn.nexus.domain.social.model.valobj.ReactionStateVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import cn.nexus.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ReactionLikeServiceTest {

    @Test
    void applyReaction_postLike_shouldUseUnifiedAtomicPathAndPublishEventLog() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = postTarget(101L);

        when(fixture.socialIdPort.nextId()).thenReturn(99L);
        when(fixture.socialIdPort.now()).thenReturn(1000L, 1000L);
        when(fixture.postAuthorPort.getPostAuthorId(101L)).thenReturn(1L);
        when(fixture.reactionCachePort.applyAtomic(1L, target, 1))
                .thenReturn(ReactionApplyResultVO.builder()
                        .currentCount(8L)
                        .delta(1)
                        .firstPending(false)
                        .build());

        ReactionResultVO result = fixture.service.applyReaction(1L, target, ReactionActionEnumVO.ADD, null);

        assertEquals(8L, result.getCurrentCount());
        assertEquals(1, result.getDelta());
        assertEquals("rid-99", result.getRequestId());
        verify(fixture.reactionCachePort).applyAtomic(1L, target, 1);
        verify(fixture.userCounterPort).increment(1L, UserCounterType.LIKE_RECEIVED, 1L);

        ArgumentCaptor<ReactionEventLogRecordVO> captor = ArgumentCaptor.forClass(ReactionEventLogRecordVO.class);
        verify(fixture.reactionEventLogMqPort).publish(captor.capture());
        ReactionEventLogRecordVO event = captor.getValue();
        assertEquals("rid-99", event.getEventId());
        assertEquals("POST", event.getTargetType());
        assertEquals(101L, event.getTargetId());
        assertEquals("LIKE", event.getReactionType());
        assertEquals(1L, event.getUserId());
        assertEquals(1, event.getDesiredState());
        assertEquals(1, event.getDelta());
        assertEquals(1000L, event.getEventTime());
    }

    @Test
    void applyReaction_commentLike_shouldUseUnifiedAtomicPathWithoutLegacyDbWrite() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = commentTarget(201L);

        when(fixture.socialIdPort.nextId()).thenReturn(199L);
        when(fixture.socialIdPort.now()).thenReturn(2000L);
        when(fixture.commentRepository.getBrief(201L))
                .thenReturn(CommentBriefVO.builder().commentId(201L).postId(301L).userId(9L).build());
        when(fixture.reactionCachePort.applyAtomic(2L, target, 1))
                .thenReturn(ReactionApplyResultVO.builder()
                        .currentCount(3L)
                        .delta(1)
                        .firstPending(false)
                        .build());

        ReactionResultVO result = fixture.service.applyReaction(2L, target, ReactionActionEnumVO.ADD, null);

        assertEquals(3L, result.getCurrentCount());
        assertEquals(1, result.getDelta());
        assertEquals("rid-199", result.getRequestId());
        verify(fixture.reactionCachePort).applyAtomic(2L, target, 1);
        verify(fixture.userCounterPort).increment(9L, UserCounterType.LIKE_RECEIVED, 1L);
        verify(fixture.reactionCommentLikeChangedMqPort).publish(org.mockito.ArgumentMatchers.<CommentLikeChangedEvent>argThat(e -> e != null
                && e.getEventId() != null
                && !e.getEventId().isBlank()
                && e.getEventId().startsWith("comment_like_changed:")
                && e.getEventId().contains(result.getRequestId())));
    }

    @Test
    void applyReaction_commentLike_withRequestId_shouldPublishDeterministicEventId() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = commentTarget(201L);

        when(fixture.socialIdPort.now()).thenReturn(2000L);
        when(fixture.commentRepository.getBrief(201L))
                .thenReturn(CommentBriefVO.builder().commentId(201L).postId(301L).userId(9L).build());
        when(fixture.reactionCachePort.applyAtomic(2L, target, 1))
                .thenReturn(ReactionApplyResultVO.builder()
                        .currentCount(3L)
                        .delta(1)
                        .firstPending(false)
                        .build());

        ReactionResultVO result = fixture.service.applyReaction(2L, target, ReactionActionEnumVO.ADD, "  req-123  ");

        assertEquals("req-123", result.getRequestId());
        verify(fixture.reactionCachePort).applyAtomic(2L, target, 1);
        ArgumentCaptor<CommentLikeChangedEvent> captor = ArgumentCaptor.forClass(CommentLikeChangedEvent.class);
        verify(fixture.reactionCommentLikeChangedMqPort).publish(captor.capture());
        assertEquals("comment_like_changed:req-123", captor.getValue().getEventId());
        assertEquals(201L, captor.getValue().getRootCommentId());
        assertEquals(301L, captor.getValue().getPostId());
        assertEquals(1L, captor.getValue().getDelta());
        assertEquals(2000L, captor.getValue().getTsMs());
    }

    @Test
    void applyReaction_commentUnlike_shouldDecrementCommentAuthorLikeReceived() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = commentTarget(201L);

        when(fixture.socialIdPort.nextId()).thenReturn(399L);
        when(fixture.socialIdPort.now()).thenReturn(4000L);
        when(fixture.commentRepository.getBrief(201L))
                .thenReturn(CommentBriefVO.builder().commentId(201L).postId(301L).userId(9L).build());
        when(fixture.reactionCachePort.applyAtomic(2L, target, 0))
                .thenReturn(ReactionApplyResultVO.builder()
                        .currentCount(2L)
                        .delta(-1)
                        .firstPending(false)
                        .build());

        ReactionResultVO result = fixture.service.applyReaction(2L, target, ReactionActionEnumVO.REMOVE, null);

        assertEquals(2L, result.getCurrentCount());
        assertEquals(-1, result.getDelta());
        verify(fixture.userCounterPort).increment(9L, UserCounterType.LIKE_RECEIVED, -1L);
    }

    @Test
    void applyReaction_commentLike_shouldSkipCounterSideEffectsWhenToggleIsIneffective() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = commentTarget(201L);

        when(fixture.socialIdPort.nextId()).thenReturn(299L);
        when(fixture.socialIdPort.now()).thenReturn(3000L);
        when(fixture.reactionCachePort.applyAtomic(2L, target, 1))
                .thenReturn(ReactionApplyResultVO.builder()
                        .currentCount(3L)
                        .delta(0)
                        .firstPending(false)
                        .build());

        ReactionResultVO result = fixture.service.applyReaction(2L, target, ReactionActionEnumVO.ADD, null);

        assertEquals(3L, result.getCurrentCount());
        assertEquals(0, result.getDelta());
        verify(fixture.reactionCommentLikeChangedMqPort, never()).publish(any(CommentLikeChangedEvent.class));
        verify(fixture.reactionNotifyMqPort, never()).publish(any());
        verify(fixture.userCounterPort, never()).increment(anyLong(), any(), anyLong());
    }

    @Test
    void applyReaction_shouldFailFastWhenCachePortReturnsNull() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = postTarget(101L);

        doReturn(99L).when(fixture.socialIdPort).nextId();
        doReturn(1000L).when(fixture.socialIdPort).now();
        when(fixture.reactionCachePort.applyAtomic(1L, target, 1)).thenReturn(null);

        AppException ex = assertThrows(AppException.class, () -> fixture.service.applyReaction(1L, target, ReactionActionEnumVO.ADD, null));

        assertEquals(ResponseCode.UN_ERROR.getCode(), ex.getCode());
        assertEquals("reaction cache apply failed", ex.getInfo());
    }

    @Test
    void queryState_shouldReadOnlyFromRedisTruth() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = postTarget(101L);
        when(fixture.reactionCachePort.getState(1L, target)).thenReturn(true);
        when(fixture.reactionCachePort.getCount(target)).thenReturn(12L);

        ReactionStateVO state = fixture.service.queryState(1L, target);

        assertEquals(true, state.isState());
        assertEquals(12L, state.getCurrentCount());
        verify(fixture.reactionCachePort).getState(1L, target);
        verify(fixture.reactionCachePort).getCount(target);
    }

    @Test
    void applyReaction_shouldNotFailWhenEventLogPublishFails() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = postTarget(101L);

        when(fixture.socialIdPort.nextId()).thenReturn(99L);
        when(fixture.socialIdPort.now()).thenReturn(1000L, 1000L);
        when(fixture.postAuthorPort.getPostAuthorId(101L)).thenReturn(1L);
        when(fixture.reactionCachePort.applyAtomic(1L, target, 1))
                .thenReturn(ReactionApplyResultVO.builder()
                        .currentCount(1L)
                        .delta(1)
                        .firstPending(false)
                        .build());
        Mockito.doThrow(new RuntimeException("mq down"))
                .when(fixture.reactionEventLogMqPort).publish(any(ReactionEventLogRecordVO.class));

        assertDoesNotThrow(() -> fixture.service.applyReaction(1L, target, ReactionActionEnumVO.ADD, null));
    }

    private static ReactionTargetVO postTarget(Long postId) {
        return ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();
    }

    private static ReactionTargetVO commentTarget(Long commentId) {
        return ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.COMMENT)
                .targetId(commentId)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();
    }

    private static final class Fixture {
        private final IReactionCachePort reactionCachePort = Mockito.mock(IReactionCachePort.class);
        private final ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        private final ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        private final IReactionCommentLikeChangedMqPort reactionCommentLikeChangedMqPort = Mockito.mock(IReactionCommentLikeChangedMqPort.class);
        private final IReactionNotifyMqPort reactionNotifyMqPort = Mockito.mock(IReactionNotifyMqPort.class);
        private final IReactionRecommendFeedbackMqPort reactionRecommendFeedbackMqPort = Mockito.mock(IReactionRecommendFeedbackMqPort.class);
        private final IReactionLikeUnlikeMqPort reactionLikeUnlikeMqPort = Mockito.mock(IReactionLikeUnlikeMqPort.class);
        private final IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        private final IReactionEventLogMqPort reactionEventLogMqPort = Mockito.mock(IReactionEventLogMqPort.class);
        private final IUserCounterPort userCounterPort = Mockito.mock(IUserCounterPort.class);
        private final ReactionLikeService service = new ReactionLikeService(
                reactionCachePort,
                commentRepository,
                socialIdPort,
                reactionCommentLikeChangedMqPort,
                reactionNotifyMqPort,
                reactionRecommendFeedbackMqPort,
                reactionLikeUnlikeMqPort,
                postAuthorPort,
                reactionEventLogMqPort,
                userCounterPort
        );
    }
}
