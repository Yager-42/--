package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IReactionNotifyMqPort;
import cn.nexus.domain.social.adapter.port.IReactionRecommendFeedbackMqPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.PostActionResultVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import cn.nexus.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PostActionServiceTest {

    @Test
    void likePostShouldDelegateToObjectCounterAndPublishPostLikeNotificationOnlyWhenChanged() {
        Fixture fixture = new Fixture();
        PostActionResultVO expected = result(true, true, false, 8L, 2L);
        when(fixture.objectCounterService.likePost(101L, 7L)).thenReturn(expected);
        when(fixture.postAuthorPort.getPostAuthorId(101L)).thenReturn(9L);
        when(fixture.socialIdPort.nextId()).thenReturn(123L);
        when(fixture.socialIdPort.now()).thenReturn(1000L);

        PostActionResultVO actual = fixture.service.likePost(101L, 7L, "req-1");

        assertSame(expected, actual);
        verify(fixture.objectCounterService).likePost(101L, 7L);
        ArgumentCaptor<InteractionNotifyEvent> captor = ArgumentCaptor.forClass(InteractionNotifyEvent.class);
        verify(fixture.reactionNotifyMqPort).publish(captor.capture());
        assertEquals(EventType.LIKE_ADDED, captor.getValue().getEventType());
        assertEquals("POST", captor.getValue().getTargetType());
        assertEquals(101L, captor.getValue().getTargetId());
        assertEquals(101L, captor.getValue().getPostId());
        assertEquals(7L, captor.getValue().getFromUserId());
    }

    @Test
    void unlikePostShouldDelegateAndPublishRecommendUnlikeOnlyWhenChanged() {
        Fixture fixture = new Fixture();
        PostActionResultVO expected = result(true, false, false, 7L, 2L);
        when(fixture.objectCounterService.unlikePost(101L, 7L)).thenReturn(expected);
        when(fixture.socialIdPort.nextId()).thenReturn(456L);
        when(fixture.socialIdPort.now()).thenReturn(2000L);

        PostActionResultVO actual = fixture.service.unlikePost(101L, 7L, "req-2");

        assertSame(expected, actual);
        verify(fixture.objectCounterService).unlikePost(101L, 7L);
        ArgumentCaptor<RecommendFeedbackEvent> captor = ArgumentCaptor.forClass(RecommendFeedbackEvent.class);
        verify(fixture.reactionRecommendFeedbackMqPort).publish(captor.capture());
        assertEquals(101L, captor.getValue().getPostId());
        assertEquals(7L, captor.getValue().getFromUserId());
        assertEquals("unlike", captor.getValue().getFeedbackType());
        verify(fixture.reactionNotifyMqPort, never()).publish(any());
    }

    @Test
    void favAndUnfavShouldDelegateWithoutNotificationOrRecommendFeedback() {
        Fixture fixture = new Fixture();
        PostActionResultVO fav = result(true, false, true, 7L, 3L);
        PostActionResultVO unfav = result(true, false, false, 7L, 2L);
        when(fixture.objectCounterService.favPost(101L, 7L)).thenReturn(fav);
        when(fixture.objectCounterService.unfavPost(101L, 7L)).thenReturn(unfav);

        assertSame(fav, fixture.service.favPost(101L, 7L, "req-3"));
        assertSame(unfav, fixture.service.unfavPost(101L, 7L, "req-4"));

        verify(fixture.objectCounterService).favPost(101L, 7L);
        verify(fixture.objectCounterService).unfavPost(101L, 7L);
        verify(fixture.reactionNotifyMqPort, never()).publish(any());
        verify(fixture.reactionRecommendFeedbackMqPort, never()).publish(any());
    }

    @Test
    void duplicateActionsShouldNotPublishSideEffects() {
        Fixture fixture = new Fixture();
        when(fixture.objectCounterService.likePost(101L, 7L)).thenReturn(result(false, true, false, 8L, 2L));
        when(fixture.objectCounterService.unlikePost(101L, 7L)).thenReturn(result(false, false, false, 7L, 2L));

        fixture.service.likePost(101L, 7L, "req-1");
        fixture.service.unlikePost(101L, 7L, "req-2");

        verify(fixture.reactionNotifyMqPort, never()).publish(any());
        verify(fixture.reactionRecommendFeedbackMqPort, never()).publish(any());
    }

    @Test
    void nullIdsShouldFailBeforeObjectCounterCall() {
        Fixture fixture = new Fixture();

        AppException ex = assertThrows(AppException.class, () -> fixture.service.likePost(null, 7L, "req"));

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), ex.getCode());
        verify(fixture.objectCounterService, never()).likePost(Mockito.anyLong(), Mockito.anyLong());
    }

    private static PostActionResultVO result(boolean changed, boolean liked, boolean faved,
                                             long likeCount, long favoriteCount) {
        return PostActionResultVO.builder()
                .changed(changed)
                .liked(liked)
                .faved(faved)
                .likeCount(likeCount)
                .favoriteCount(favoriteCount)
                .build();
    }

    private static final class Fixture {
        private final IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        private final ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        private final IReactionNotifyMqPort reactionNotifyMqPort = Mockito.mock(IReactionNotifyMqPort.class);
        private final IReactionRecommendFeedbackMqPort reactionRecommendFeedbackMqPort = Mockito.mock(IReactionRecommendFeedbackMqPort.class);
        private final IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        private final PostActionService service = new PostActionService(
                objectCounterService,
                socialIdPort,
                reactionNotifyMqPort,
                reactionRecommendFeedbackMqPort,
                postAuthorPort);
    }
}
