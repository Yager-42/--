package cn.nexus.trigger.job.social;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import cn.nexus.infrastructure.adapter.social.repository.ReactionEventLogRepository;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionEventLogPO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ReactionRedisRecoveryRunnerTest {

    @Test
    void recoverFamily_shouldAdvanceCheckpointAndRebuildPostLikeAndLikeReceived() {
        ReactionEventLogRepository repository = Mockito.mock(ReactionEventLogRepository.class);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:replay:checkpoint:POST:LIKE")).thenReturn("0");
        when(repository.pageAfterSeq("POST", "LIKE", 0L, 2)).thenReturn(List.of(
                event(11L, "evt-1", "POST", 101L, 7L, 1, 1),
                event(12L, "evt-2", "POST", 101L, 8L, 0, -1)
        )).thenReturn(List.of());
        when(valueOperations.getBit("count:fact:post_like:{101}:0", 7L)).thenReturn(false);
        when(valueOperations.getBit("count:fact:post_like:{101}:0", 8L)).thenReturn(true);
        when(valueOperations.get("count:post:{101}"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{4L}, 1)))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{5L}, 1)));
        when(valueOperations.get("count:user:{900}"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 0L, 0L, 3L, 0L}, 5)))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 0L, 0L, 4L, 0L}, 5)));
        when(contentRepository.findPost(101L)).thenReturn(ContentPostEntity.builder().postId(101L).userId(900L).build());

        ReactionRedisRecoveryRunner runner = new ReactionRedisRecoveryRunner(repository, redisTemplate, contentRepository, commentRepository, 2);

        boolean success = runner.recoverFamily("POST", "LIKE");

        assertTrue(success);
        verify(valueOperations).setBit("count:fact:post_like:{101}:0", 7L, true);
        verify(valueOperations).setBit("count:fact:post_like:{101}:0", 8L, false);
        verify(valueOperations).set(eq("count:post:{101}"),
                eq(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{5L}, 1))));
        verify(valueOperations).set(eq("count:post:{101}"),
                eq(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{4L}, 1))));
        verify(valueOperations).set(eq("count:user:{900}"),
                eq(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 0L, 0L, 4L, 0L}, 5))));
        verify(valueOperations).set(eq("count:user:{900}"),
                eq(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 0L, 0L, 3L, 0L}, 5))));
        verify(valueOperations).set("count:replay:checkpoint:POST:LIKE", "12");
    }

    @Test
    void recoverFamily_shouldKeepCheckpointWhenOneEventFails() {
        ReactionEventLogRepository repository = Mockito.mock(ReactionEventLogRepository.class);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:replay:checkpoint:POST:LIKE")).thenReturn("5");
        when(repository.pageAfterSeq("POST", "LIKE", 5L, 2)).thenReturn(List.of(
                event(11L, "evt-1", "POST", 101L, 7L, 1, 1),
                event(12L, "evt-2", "POST", 202L, 8L, 1, 1)
        ));
        when(valueOperations.getBit("count:fact:post_like:{101}:0", 7L)).thenReturn(false);
        when(valueOperations.get("count:post:{101}"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{4L}, 1)));
        when(valueOperations.get("count:user:{900}"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 0L, 0L, 3L, 0L}, 5)));
        when(contentRepository.findPost(101L)).thenReturn(ContentPostEntity.builder().postId(101L).userId(900L).build());
        when(contentRepository.findPost(202L)).thenReturn(null);

        ReactionRedisRecoveryRunner runner = new ReactionRedisRecoveryRunner(repository, redisTemplate, contentRepository, commentRepository, 2);

        boolean success = runner.recoverFamily("POST", "LIKE");

        assertFalse(success);
        verify(valueOperations, never()).set("count:replay:checkpoint:POST:LIKE", "12");
    }

    @Test
    void recoverFamily_shouldUseCommentOwnerForCommentLikeReplay() {
        ReactionEventLogRepository repository = Mockito.mock(ReactionEventLogRepository.class);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:replay:checkpoint:COMMENT:LIKE")).thenReturn("0");
        when(repository.pageAfterSeq("COMMENT", "LIKE", 0L, 1)).thenReturn(List.of(
                event(21L, "evt-21", "COMMENT", 501L, 9L, 1, 1)
        )).thenReturn(List.of());
        when(valueOperations.getBit("count:fact:comment_like:{501}:0", 9L)).thenReturn(false);
        when(valueOperations.get("count:comment:{501}"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{5L, 11L}, 2)));
        when(valueOperations.get("count:user:{901}"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 0L, 0L, 2L, 0L}, 5)));
        when(commentRepository.getBrief(501L))
                .thenReturn(CommentBriefVO.builder().commentId(501L).postId(1001L).userId(901L).status(1).build());

        ReactionRedisRecoveryRunner runner = new ReactionRedisRecoveryRunner(repository, redisTemplate, contentRepository, commentRepository, 1);

        boolean success = runner.recoverFamily("COMMENT", "LIKE");

        assertTrue(success);
        verify(valueOperations).setBit("count:fact:comment_like:{501}:0", 9L, true);
        verify(valueOperations).set(eq("count:comment:{501}"),
                eq(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{6L, 11L}, 2))));
        verify(valueOperations).set(eq("count:user:{901}"),
                eq(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 0L, 0L, 3L, 0L}, 5))));
        verify(valueOperations).set("count:replay:checkpoint:COMMENT:LIKE", "21");
        verify(contentRepository, never()).findPost(any());
    }

    private InteractionReactionEventLogPO event(long seq,
                                                String eventId,
                                                String targetType,
                                                long targetId,
                                                long userId,
                                                int desiredState,
                                                int delta) {
        InteractionReactionEventLogPO po = new InteractionReactionEventLogPO();
        po.setSeq(seq);
        po.setEventId(eventId);
        po.setTargetType(targetType);
        po.setTargetId(targetId);
        po.setReactionType("LIKE");
        po.setUserId(userId);
        po.setDesiredState(desiredState);
        po.setDelta(delta);
        po.setEventTime(1000L + seq);
        return po;
    }
}
