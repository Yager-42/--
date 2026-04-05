package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.ICommentEventPort;
import cn.nexus.domain.social.adapter.port.IInteractionNotifyEventPort;
import cn.nexus.domain.social.adapter.port.ILikeUnlikeEventPort;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IPostLikeCachePort;
import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.adapter.port.IReactionDelayPort;
import cn.nexus.domain.social.adapter.port.IRecommendFeedbackEventPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.ReactionActionEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReactionLikeServiceTest {

    @Test
    void applyReaction_postLike_shouldUseUnifiedAtomicPathAndScheduleSyncOnFirstPending() {
        IReactionCachePort reactionCachePort = Mockito.mock(IReactionCachePort.class);
        IReactionDelayPort reactionDelayPort = Mockito.mock(IReactionDelayPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        IReactionRepository reactionRepository = Mockito.mock(IReactionRepository.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        ICommentEventPort commentEventPort = Mockito.mock(ICommentEventPort.class);
        IInteractionNotifyEventPort interactionNotifyEventPort = Mockito.mock(IInteractionNotifyEventPort.class);
        IRecommendFeedbackEventPort recommendFeedbackEventPort = Mockito.mock(IRecommendFeedbackEventPort.class);
        IPostLikeCachePort postLikeCachePort = Mockito.mock(IPostLikeCachePort.class);
        ILikeUnlikeEventPort likeUnlikeEventPort = Mockito.mock(ILikeUnlikeEventPort.class);
        IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);

        ReactionLikeService service = new ReactionLikeService(
                reactionCachePort,
                reactionDelayPort,
                commentRepository,
                reactionRepository,
                socialIdPort,
                commentEventPort,
                interactionNotifyEventPort,
                recommendFeedbackEventPort,
                postLikeCachePort,
                likeUnlikeEventPort,
                postAuthorPort,
                userBaseRepository
        );

        ReactionTargetVO target = ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(101L)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();

        when(socialIdPort.nextId()).thenReturn(99L);
        when(socialIdPort.now()).thenReturn(1000L, 1000L);
        when(postAuthorPort.getPostAuthorId(101L)).thenReturn(1L);
        when(reactionCachePort.applyAtomic(1L, target, 1, 600))
                .thenReturn(ReactionApplyResultVO.builder()
                        .currentCount(8L)
                        .delta(1)
                        .firstPending(true)
                        .build());
        when(reactionCachePort.getWindowMs(target, 300_000L)).thenReturn(5000L);

        ReactionResultVO result = service.applyReaction(1L, target, ReactionActionEnumVO.ADD, null);

        assertEquals(8L, result.getCurrentCount());
        assertEquals(1, result.getDelta());
        verify(reactionCachePort).applyAtomic(1L, target, 1, 600);
        verify(reactionDelayPort).sendDelay(target, 5000L);
        verify(postLikeCachePort).applyCreatorLikeDelta(1L, 1);
    }

    @Test
    void applyReaction_commentLike_shouldUseUnifiedAtomicPathWithoutLegacyDbWrite() {
        IReactionCachePort reactionCachePort = Mockito.mock(IReactionCachePort.class);
        IReactionDelayPort reactionDelayPort = Mockito.mock(IReactionDelayPort.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        IReactionRepository reactionRepository = Mockito.mock(IReactionRepository.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        ICommentEventPort commentEventPort = Mockito.mock(ICommentEventPort.class);
        IInteractionNotifyEventPort interactionNotifyEventPort = Mockito.mock(IInteractionNotifyEventPort.class);
        IRecommendFeedbackEventPort recommendFeedbackEventPort = Mockito.mock(IRecommendFeedbackEventPort.class);
        IPostLikeCachePort postLikeCachePort = Mockito.mock(IPostLikeCachePort.class);
        ILikeUnlikeEventPort likeUnlikeEventPort = Mockito.mock(ILikeUnlikeEventPort.class);
        IPostAuthorPort postAuthorPort = Mockito.mock(IPostAuthorPort.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);

        ReactionLikeService service = new ReactionLikeService(
                reactionCachePort,
                reactionDelayPort,
                commentRepository,
                reactionRepository,
                socialIdPort,
                commentEventPort,
                interactionNotifyEventPort,
                recommendFeedbackEventPort,
                postLikeCachePort,
                likeUnlikeEventPort,
                postAuthorPort,
                userBaseRepository
        );

        ReactionTargetVO target = ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.COMMENT)
                .targetId(201L)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();

        when(socialIdPort.nextId()).thenReturn(199L);
        when(socialIdPort.now()).thenReturn(2000L, 2000L);
        when(commentRepository.getBrief(201L))
                .thenReturn(CommentBriefVO.builder().commentId(201L).postId(301L).build());
        when(reactionCachePort.getWindowMs(target, 1_000L)).thenReturn(1_000L);
        when(reactionCachePort.applyAtomic(2L, target, 1, 600))
                .thenReturn(ReactionApplyResultVO.builder()
                        .currentCount(3L)
                        .delta(1)
                        .firstPending(true)
                        .build());

        ReactionResultVO result = service.applyReaction(2L, target, ReactionActionEnumVO.ADD, null);

        assertEquals(3L, result.getCurrentCount());
        assertEquals(1, result.getDelta());
        verify(reactionCachePort).applyAtomic(2L, target, 1, 600);
        verify(reactionDelayPort).sendDelay(target, 1_000L);
        verify(reactionRepository, never()).insertIgnore(any(), any());
        verify(reactionRepository, never()).incrCount(any(), anyLong());
        verify(postLikeCachePort, never()).applyCreatorLikeDelta(Mockito.anyLong(), Mockito.anyInt());
        verify(commentEventPort).publish(org.mockito.ArgumentMatchers.<CommentLikeChangedEvent>argThat(e -> e != null
                && e.getEventId() != null
                && !e.getEventId().isBlank()
                && e.getEventId().startsWith("comment_like_changed:")
                && e.getEventId().contains(result.getRequestId())));
    }
}
