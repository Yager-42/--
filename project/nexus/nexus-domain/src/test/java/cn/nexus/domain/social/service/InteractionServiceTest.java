package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
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
import cn.nexus.domain.social.model.valobj.CommentResultVO;
import cn.nexus.domain.social.model.valobj.RiskDecisionVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
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
}
