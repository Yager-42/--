package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.event.CounterEvent;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IReactionCommentLikeChangedMqPort;
import cn.nexus.domain.social.adapter.port.IReactionNotifyMqPort;
import cn.nexus.domain.social.adapter.port.IReactionRecommendFeedbackMqPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.ReactionActionEnumVO;
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
import org.springframework.context.ApplicationEventPublisher;

class ReactionLikeServiceTest {

    @Test
    void applyReaction_postLike_shouldUseUnifiedAtomicPath() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = postTarget(101L);

        when(fixture.socialIdPort.nextId()).thenReturn(99L);
        when(fixture.socialIdPort.now()).thenReturn(1000L, 1000L);
        when(fixture.postAuthorPort.getPostAuthorId(101L)).thenReturn(1L);
        when(fixture.objectCounterService.like(ReactionTargetTypeEnumVO.POST, 101L, 1L)).thenReturn(true);
        when(fixture.objectCounterService.getCounts(ReactionTargetTypeEnumVO.POST, 101L, java.util.List.of(cn.nexus.domain.counter.model.valobj.ObjectCounterType.LIKE)))
                .thenReturn(java.util.Map.of("like", 8L));

        ReactionResultVO result = fixture.service.applyReaction(1L, target, ReactionActionEnumVO.ADD, null);

        assertEquals(8L, result.getCurrentCount());
        assertEquals(1, result.getDelta());
        assertEquals("rid-99", result.getRequestId());
        verify(fixture.objectCounterService).like(ReactionTargetTypeEnumVO.POST, 101L, 1L);
        verifyPublishedCounterEvent(fixture, "rid-99", ReactionTargetTypeEnumVO.POST, 101L, 1L);
    }

    @Test
    void applyReaction_commentLike_shouldUseUnifiedAtomicPathWithoutLegacyDbWrite() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = commentTarget(201L);

        when(fixture.socialIdPort.nextId()).thenReturn(199L);
        when(fixture.socialIdPort.now()).thenReturn(2000L);
        when(fixture.commentRepository.getBrief(201L))
                .thenReturn(CommentBriefVO.builder().commentId(201L).postId(301L).userId(9L).build());
        when(fixture.objectCounterService.like(ReactionTargetTypeEnumVO.COMMENT, 201L, 2L)).thenReturn(true);
        when(fixture.objectCounterService.getCounts(ReactionTargetTypeEnumVO.COMMENT, 201L, java.util.List.of(cn.nexus.domain.counter.model.valobj.ObjectCounterType.LIKE)))
                .thenReturn(java.util.Map.of("like", 3L));

        ReactionResultVO result = fixture.service.applyReaction(2L, target, ReactionActionEnumVO.ADD, null);

        assertEquals(3L, result.getCurrentCount());
        assertEquals(1, result.getDelta());
        assertEquals("rid-199", result.getRequestId());
        verify(fixture.objectCounterService).like(ReactionTargetTypeEnumVO.COMMENT, 201L, 2L);
        verifyPublishedCounterEvent(fixture, "rid-199", ReactionTargetTypeEnumVO.COMMENT, 201L, 2L);
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
        when(fixture.objectCounterService.like(ReactionTargetTypeEnumVO.COMMENT, 201L, 2L)).thenReturn(true);
        when(fixture.objectCounterService.getCounts(ReactionTargetTypeEnumVO.COMMENT, 201L, java.util.List.of(cn.nexus.domain.counter.model.valobj.ObjectCounterType.LIKE)))
                .thenReturn(java.util.Map.of("like", 3L));

        ReactionResultVO result = fixture.service.applyReaction(2L, target, ReactionActionEnumVO.ADD, "  req-123  ");

        assertEquals("req-123", result.getRequestId());
        verify(fixture.objectCounterService).like(ReactionTargetTypeEnumVO.COMMENT, 201L, 2L);
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
        when(fixture.objectCounterService.unlike(ReactionTargetTypeEnumVO.COMMENT, 201L, 2L)).thenReturn(true);
        when(fixture.objectCounterService.getCounts(ReactionTargetTypeEnumVO.COMMENT, 201L, java.util.List.of(cn.nexus.domain.counter.model.valobj.ObjectCounterType.LIKE)))
                .thenReturn(java.util.Map.of("like", 2L));

        ReactionResultVO result = fixture.service.applyReaction(2L, target, ReactionActionEnumVO.REMOVE, null);

        assertEquals(2L, result.getCurrentCount());
        assertEquals(-1, result.getDelta());
        verifyPublishedCounterEvent(fixture, "rid-399", ReactionTargetTypeEnumVO.COMMENT, 201L, 2L);
    }

    @Test
    void applyReaction_commentLike_shouldSkipCounterSideEffectsWhenToggleIsIneffective() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = commentTarget(201L);

        when(fixture.socialIdPort.nextId()).thenReturn(299L);
        when(fixture.socialIdPort.now()).thenReturn(3000L);
        when(fixture.objectCounterService.like(ReactionTargetTypeEnumVO.COMMENT, 201L, 2L)).thenReturn(false);
        when(fixture.objectCounterService.getCounts(ReactionTargetTypeEnumVO.COMMENT, 201L, java.util.List.of(cn.nexus.domain.counter.model.valobj.ObjectCounterType.LIKE)))
                .thenReturn(java.util.Map.of("like", 3L));

        ReactionResultVO result = fixture.service.applyReaction(2L, target, ReactionActionEnumVO.ADD, null);

        assertEquals(3L, result.getCurrentCount());
        assertEquals(0, result.getDelta());
        verify(fixture.reactionCommentLikeChangedMqPort, never()).publish(any(CommentLikeChangedEvent.class));
        verify(fixture.reactionNotifyMqPort, never()).publish(any());
        verify(fixture.applicationEventPublisher, never()).publishEvent(any(CounterEvent.class));
    }

    @Test
    void applyReaction_shouldFailFastWhenCachePortReturnsNull() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = postTarget(101L);

        doReturn(99L).when(fixture.socialIdPort).nextId();
        doReturn(1000L).when(fixture.socialIdPort).now();
        when(fixture.objectCounterService.like(ReactionTargetTypeEnumVO.POST, 101L, 1L)).thenThrow(new RuntimeException("boom"));

        AppException ex = assertThrows(AppException.class, () -> fixture.service.applyReaction(1L, target, ReactionActionEnumVO.ADD, null));

        assertEquals(ResponseCode.UN_ERROR.getCode(), ex.getCode());
        assertEquals(ResponseCode.UN_ERROR.getInfo(), ex.getInfo());
    }

    @Test
    void queryState_shouldReadOnlyFromRedisTruth() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = postTarget(101L);
        when(fixture.objectCounterService.isLiked(ReactionTargetTypeEnumVO.POST, 101L, 1L)).thenReturn(true);
        when(fixture.objectCounterService.getCounts(ReactionTargetTypeEnumVO.POST, 101L, java.util.List.of(cn.nexus.domain.counter.model.valobj.ObjectCounterType.LIKE)))
                .thenReturn(java.util.Map.of("like", 12L));

        ReactionStateVO state = fixture.service.queryState(1L, target);

        assertEquals(true, state.isState());
        assertEquals(12L, state.getCurrentCount());
        verify(fixture.objectCounterService).isLiked(ReactionTargetTypeEnumVO.POST, 101L, 1L);
    }

    @Test
    void applyReaction_shouldIgnorePostSideEffectFailures() {
        Fixture fixture = new Fixture();
        ReactionTargetVO target = postTarget(101L);

        when(fixture.socialIdPort.nextId()).thenReturn(99L);
        when(fixture.socialIdPort.now()).thenReturn(1000L, 1000L);
        when(fixture.postAuthorPort.getPostAuthorId(101L)).thenReturn(1L);
        when(fixture.objectCounterService.like(ReactionTargetTypeEnumVO.POST, 101L, 1L)).thenReturn(true);
        when(fixture.objectCounterService.getCounts(ReactionTargetTypeEnumVO.POST, 101L, java.util.List.of(cn.nexus.domain.counter.model.valobj.ObjectCounterType.LIKE)))
                .thenReturn(java.util.Map.of("like", 1L));
        Mockito.doThrow(new RuntimeException("mq down"))
                .when(fixture.reactionNotifyMqPort).publish(any());

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
        private final IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        private final ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        private final ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        private final IReactionCommentLikeChangedMqPort reactionCommentLikeChangedMqPort = Mockito.mock(IReactionCommentLikeChangedMqPort.class);
        private final IReactionNotifyMqPort reactionNotifyMqPort = Mockito.mock(IReactionNotifyMqPort.class);
        private final IReactionRecommendFeedbackMqPort reactionRecommendFeedbackMqPort = Mockito.mock(IReactionRecommendFeedbackMqPort.class);
        private final IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        private final ApplicationEventPublisher applicationEventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        private final ReactionLikeService service = new ReactionLikeService(
                objectCounterService,
                commentRepository,
                socialIdPort,
                reactionCommentLikeChangedMqPort,
                reactionNotifyMqPort,
                reactionRecommendFeedbackMqPort,
                postAuthorPort,
                applicationEventPublisher
        );
    }

    private static void verifyPublishedCounterEvent(Fixture fixture,
                                                    String expectedRequestId,
                                                    ReactionTargetTypeEnumVO expectedTargetType,
                                                    Long expectedTargetId,
                                                    Long expectedActorUserId) {
        ArgumentCaptor<CounterEvent> eventCaptor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(fixture.applicationEventPublisher).publishEvent(eventCaptor.capture());
        CounterEvent event = eventCaptor.getValue();
        assertEquals(expectedRequestId, event.getRequestId());
        assertEquals(expectedTargetType, event.getTargetType());
        assertEquals(expectedTargetId, event.getTargetId());
        assertEquals(ObjectCounterType.LIKE, event.getCounterType());
        assertEquals(expectedActorUserId, event.getActorUserId());
    }
}
