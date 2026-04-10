package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.ICommentEventPort;
import cn.nexus.domain.social.adapter.port.IInteractionNotifyEventPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentPinRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IInteractionNotificationRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.CommentResultVO;
import cn.nexus.domain.social.model.valobj.CommentViewVO;
import cn.nexus.domain.social.model.valobj.NotificationListVO;
import cn.nexus.domain.social.model.valobj.NotificationVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.ReactionResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.RiskDecisionVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.types.event.interaction.CommentCreatedEvent;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import cn.nexus.types.event.interaction.RootReplyCountChangedEvent;
import cn.nexus.types.exception.AppException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class InteractionServiceTest {

    @Test
    void comment_shouldPublishMentionEventsForResolvedUsernames() {
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        IReactionLikeService reactionLikeService = Mockito.mock(IReactionLikeService.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentPinRepository commentPinRepository = Mockito.mock(ICommentPinRepository.class);
        ICommentHotRankRepository commentHotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IRiskService riskService = Mockito.mock(IRiskService.class);
        ICommentEventPort commentEventPort = Mockito.mock(ICommentEventPort.class);
        IInteractionNotifyEventPort interactionNotifyEventPort = Mockito.mock(IInteractionNotifyEventPort.class);
        IInteractionNotificationRepository interactionNotificationRepository = Mockito.mock(IInteractionNotificationRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);

        InteractionService service = new InteractionService(
                socialIdPort,
                reactionLikeService,
                commentRepository,
                commentPinRepository,
                commentHotRankRepository,
                contentRepository,
                riskService,
                commentEventPort,
                interactionNotifyEventPort,
                interactionNotificationRepository,
                userBaseRepository);

        when(socialIdPort.now()).thenReturn(1000L);
        when(socialIdPort.nextId()).thenReturn(2000L);
        when(riskService.decision(any())).thenReturn(RiskDecisionVO.builder().result("PASS").build());
        when(userBaseRepository.listByUsernames(Mockito.argThat(list ->
                list != null && list.containsAll(List.of("alice", "bob", "me")) && list.size() == 3)))
                .thenReturn(List.of(
                        UserBriefVO.builder().userId(11L).nickname("alice").build(),
                        UserBriefVO.builder().userId(12L).nickname("bob").build(),
                        UserBriefVO.builder().userId(1L).nickname("me").build()
                ));

        CommentResultVO result = service.comment(1L, 9L, null, "hello @alice, and @bob！ plus @me", null);

        assertNotNull(result);
        assertEquals("OK", result.getStatus());
        assertEquals(2000L, result.getCommentId());

        ArgumentCaptor<InteractionNotifyEvent> captor = ArgumentCaptor.forClass(InteractionNotifyEvent.class);
        verify(interactionNotifyEventPort, Mockito.times(3)).publish(captor.capture());
        List<InteractionNotifyEvent> mentionEvents = captor.getAllValues().stream()
                .filter(event -> event.getEventType() == EventType.COMMENT_MENTIONED)
                .collect(Collectors.toList());
        assertEquals(2, mentionEvents.size());
        assertEquals(List.of(11L, 12L), mentionEvents.stream()
                .map(InteractionNotifyEvent::getToUserId)
                .sorted()
                .collect(Collectors.toList()));
        assertEquals(List.of("POST", "POST"), mentionEvents.stream()
                .map(InteractionNotifyEvent::getTargetType)
                .collect(Collectors.toList()));
    }

    @Test
    void comment_shouldReturnPendingReviewWithoutSideEffects() {
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        IReactionLikeService reactionLikeService = Mockito.mock(IReactionLikeService.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentPinRepository commentPinRepository = Mockito.mock(ICommentPinRepository.class);
        ICommentHotRankRepository commentHotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IRiskService riskService = Mockito.mock(IRiskService.class);
        ICommentEventPort commentEventPort = Mockito.mock(ICommentEventPort.class);
        IInteractionNotifyEventPort interactionNotifyEventPort = Mockito.mock(IInteractionNotifyEventPort.class);
        IInteractionNotificationRepository interactionNotificationRepository = Mockito.mock(IInteractionNotificationRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);

        InteractionService service = new InteractionService(socialIdPort, reactionLikeService, commentRepository, commentPinRepository,
                commentHotRankRepository, contentRepository, riskService, commentEventPort, interactionNotifyEventPort,
                interactionNotificationRepository, userBaseRepository);

        when(socialIdPort.now()).thenReturn(1000L);
        when(socialIdPort.nextId()).thenReturn(2000L);
        when(riskService.decision(any())).thenReturn(RiskDecisionVO.builder().result("REVIEW").build());

        CommentResultVO result = service.comment(1L, 9L, null, "need review", null);

        assertEquals("PENDING_REVIEW", result.getStatus());
        Mockito.verifyNoInteractions(commentEventPort, interactionNotifyEventPort);
    }

    @Test
    void react_shouldDelegateCommentTargetWithoutPublishingCommentLikeChangedEvent() {
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        IReactionLikeService reactionLikeService = Mockito.mock(IReactionLikeService.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentPinRepository commentPinRepository = Mockito.mock(ICommentPinRepository.class);
        ICommentHotRankRepository commentHotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IRiskService riskService = Mockito.mock(IRiskService.class);
        ICommentEventPort commentEventPort = Mockito.mock(ICommentEventPort.class);
        IInteractionNotifyEventPort interactionNotifyEventPort = Mockito.mock(IInteractionNotifyEventPort.class);
        IInteractionNotificationRepository interactionNotificationRepository = Mockito.mock(IInteractionNotificationRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);

        InteractionService service = new InteractionService(socialIdPort, reactionLikeService, commentRepository, commentPinRepository,
                commentHotRankRepository, contentRepository, riskService, commentEventPort, interactionNotifyEventPort,
                interactionNotificationRepository, userBaseRepository);

        when(commentRepository.getBrief(101L)).thenReturn(CommentBriefVO.builder().commentId(101L).postId(9L).status(1).build());
        when(reactionLikeService.applyReaction(any(), any(), any(), any()))
                .thenReturn(ReactionResultVO.builder().delta(1).currentCount(3L).build());

        service.react(1L, 101L, "COMMENT", "LIKE", "ADD", "req-1");

        ArgumentCaptor<ReactionTargetVO> captor = ArgumentCaptor.forClass(ReactionTargetVO.class);
        verify(reactionLikeService).applyReaction(Mockito.eq(1L), captor.capture(), Mockito.any(), Mockito.eq("req-1"));
        assertEquals(101L, captor.getValue().getTargetId());
        assertEquals("COMMENT", captor.getValue().getTargetType().getCode());
        assertEquals("LIKE", captor.getValue().getReactionType().getCode());
        Mockito.verifyNoInteractions(commentEventPort, interactionNotifyEventPort);
    }

    @Test
    void notifications_shouldRenderContentAndCursor() {
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        IReactionLikeService reactionLikeService = Mockito.mock(IReactionLikeService.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentPinRepository commentPinRepository = Mockito.mock(ICommentPinRepository.class);
        ICommentHotRankRepository commentHotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IRiskService riskService = Mockito.mock(IRiskService.class);
        ICommentEventPort commentEventPort = Mockito.mock(ICommentEventPort.class);
        IInteractionNotifyEventPort interactionNotifyEventPort = Mockito.mock(IInteractionNotifyEventPort.class);
        IInteractionNotificationRepository interactionNotificationRepository = Mockito.mock(IInteractionNotificationRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);

        InteractionService service = new InteractionService(socialIdPort, reactionLikeService, commentRepository, commentPinRepository,
                commentHotRankRepository, contentRepository, riskService, commentEventPort, interactionNotifyEventPort,
                interactionNotificationRepository, userBaseRepository);
        when(interactionNotificationRepository.pageByUser(1L, null, 20)).thenReturn(List.of(
                NotificationVO.builder().notificationId(99L).bizType("COMMENT_LIKED").unreadCount(2L).createTime(1000L).build()
        ));

        NotificationListVO result = service.notifications(1L, null);

        assertEquals(1, result.getNotifications().size());
        assertNull(result.getNextCursor());
    }

    @Test
    void reactionState_shouldRejectInvalidTarget() {
        InteractionService service = new InteractionService(
                Mockito.mock(ISocialIdPort.class),
                Mockito.mock(IReactionLikeService.class),
                Mockito.mock(ICommentRepository.class),
                Mockito.mock(ICommentPinRepository.class),
                Mockito.mock(ICommentHotRankRepository.class),
                Mockito.mock(IContentRepository.class),
                Mockito.mock(IRiskService.class),
                Mockito.mock(ICommentEventPort.class),
                Mockito.mock(IInteractionNotifyEventPort.class),
                Mockito.mock(IInteractionNotificationRepository.class),
                Mockito.mock(IUserBaseRepository.class));

        assertThrows(AppException.class, () -> service.reactionState(1L, null, "POST", "LIKE"));
    }

    @Test
    void deleteComment_shouldReturnDeletedWhenCommentDoesNotExist() {
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        InteractionService service = new InteractionService(
                socialIdPort,
                Mockito.mock(IReactionLikeService.class),
                commentRepository,
                Mockito.mock(ICommentPinRepository.class),
                Mockito.mock(ICommentHotRankRepository.class),
                Mockito.mock(IContentRepository.class),
                Mockito.mock(IRiskService.class),
                Mockito.mock(ICommentEventPort.class),
                Mockito.mock(IInteractionNotifyEventPort.class),
                Mockito.mock(IInteractionNotificationRepository.class),
                Mockito.mock(IUserBaseRepository.class));
        when(commentRepository.getBrief(88L)).thenReturn(null);

        assertEquals("DELETED", service.deleteComment(1L, 88L).getStatus());
    }

    @Test
    void readAllNotifications_shouldMarkAllAsRead() {
        IInteractionNotificationRepository interactionNotificationRepository = Mockito.mock(IInteractionNotificationRepository.class);
        InteractionService service = new InteractionService(
                Mockito.mock(ISocialIdPort.class),
                Mockito.mock(IReactionLikeService.class),
                Mockito.mock(ICommentRepository.class),
                Mockito.mock(ICommentPinRepository.class),
                Mockito.mock(ICommentHotRankRepository.class),
                Mockito.mock(IContentRepository.class),
                Mockito.mock(IRiskService.class),
                Mockito.mock(ICommentEventPort.class),
                Mockito.mock(IInteractionNotifyEventPort.class),
                interactionNotificationRepository,
                Mockito.mock(IUserBaseRepository.class));

        assertEquals("READ_ALL", service.readAllNotifications(9L).getStatus());
        verify(interactionNotificationRepository).markReadAll(9L);
    }

    @Test
    void applyCommentRiskReviewResult_shouldApprovePendingReplyAndPublishEvents() {
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentEventPort commentEventPort = Mockito.mock(ICommentEventPort.class);
        IInteractionNotifyEventPort interactionNotifyEventPort = Mockito.mock(IInteractionNotifyEventPort.class);
        InteractionService service = new InteractionService(
                socialIdPort,
                Mockito.mock(IReactionLikeService.class),
                commentRepository,
                Mockito.mock(ICommentPinRepository.class),
                Mockito.mock(ICommentHotRankRepository.class),
                Mockito.mock(IContentRepository.class),
                Mockito.mock(IRiskService.class),
                commentEventPort,
                interactionNotifyEventPort,
                Mockito.mock(IInteractionNotificationRepository.class),
                Mockito.mock(IUserBaseRepository.class));
        when(socialIdPort.now()).thenReturn(1000L);
        when(commentRepository.listByIds(List.of(101L))).thenReturn(List.of(
                CommentViewVO.builder()
                        .commentId(101L)
                        .postId(9L)
                        .userId(2L)
                        .rootId(88L)
                        .parentId(77L)
                        .content("reply body")
                        .status(0)
                        .build()
        ));
        when(commentRepository.approvePending(101L, 1000L)).thenReturn(true);

        OperationResultVO result = service.applyCommentRiskReviewResult(101L, "PASS", "MANUAL");

        assertEquals("APPROVED", result.getStatus());
        verify(commentEventPort).publish(Mockito.any(CommentCreatedEvent.class));
        verify(commentEventPort).publish(Mockito.any(RootReplyCountChangedEvent.class));
        verify(interactionNotifyEventPort).publish(Mockito.any(InteractionNotifyEvent.class));
    }

    @Test
    void pinComment_shouldPinRootCommentForPostOwner() {
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentPinRepository commentPinRepository = Mockito.mock(ICommentPinRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        InteractionService service = new InteractionService(
                socialIdPort,
                Mockito.mock(IReactionLikeService.class),
                commentRepository,
                commentPinRepository,
                Mockito.mock(ICommentHotRankRepository.class),
                contentRepository,
                Mockito.mock(IRiskService.class),
                Mockito.mock(ICommentEventPort.class),
                Mockito.mock(IInteractionNotifyEventPort.class),
                Mockito.mock(IInteractionNotificationRepository.class),
                Mockito.mock(IUserBaseRepository.class));
        when(socialIdPort.now()).thenReturn(1000L);
        when(contentRepository.findPost(9L)).thenReturn(cn.nexus.domain.social.model.entity.ContentPostEntity.builder()
                .postId(9L)
                .userId(1L)
                .build());
        when(commentRepository.getBrief(101L)).thenReturn(CommentBriefVO.builder()
                .commentId(101L)
                .postId(9L)
                .status(1)
                .build());

        OperationResultVO result = service.pinComment(1L, 101L, 9L);

        assertEquals("PINNED", result.getStatus());
        verify(commentPinRepository).pin(9L, 101L, 1000L);
    }

    @Test
    void readNotification_shouldMarkSingleNotificationAsRead() {
        IInteractionNotificationRepository interactionNotificationRepository = Mockito.mock(IInteractionNotificationRepository.class);
        InteractionService service = new InteractionService(
                Mockito.mock(ISocialIdPort.class),
                Mockito.mock(IReactionLikeService.class),
                Mockito.mock(ICommentRepository.class),
                Mockito.mock(ICommentPinRepository.class),
                Mockito.mock(ICommentHotRankRepository.class),
                Mockito.mock(IContentRepository.class),
                Mockito.mock(IRiskService.class),
                Mockito.mock(ICommentEventPort.class),
                Mockito.mock(IInteractionNotifyEventPort.class),
                interactionNotificationRepository,
                Mockito.mock(IUserBaseRepository.class));

        OperationResultVO result = service.readNotification(9L, 88L);

        assertEquals("READ", result.getStatus());
        verify(interactionNotificationRepository).markRead(9L, 88L);
    }
}
