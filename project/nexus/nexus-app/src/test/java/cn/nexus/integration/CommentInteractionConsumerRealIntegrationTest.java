package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import cn.nexus.infrastructure.dao.social.ICommentDao;
import cn.nexus.infrastructure.dao.social.po.CommentPO;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import cn.nexus.types.event.interaction.RootReplyCountChangedEvent;
import java.time.Duration;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommentInteractionConsumerRealIntegrationTest extends RealMiddlewareIntegrationTestSupport {

    @Autowired
    private ICommentDao commentDao;

    @Test
    void commentLikeChangedEvent_shouldUpdateRootCommentAndRemainIdempotent() {
        long postId = uniqueId();
        long rootCommentId = uniqueId();
        insertRootComment(postId, rootCommentId);
        commentHotRankRepository.clear(postId);

        CommentLikeChangedEvent event = new CommentLikeChangedEvent();
        event.setEventId("like-" + uniqueUuid());
        event.setPostId(postId);
        event.setRootCommentId(rootCommentId);
        event.setDelta(3L);
        event.setTsMs(System.currentTimeMillis());

        rabbitTemplate.convertAndSend(
                InteractionCommentMqConfig.EXCHANGE,
                InteractionCommentMqConfig.RK_COMMENT_LIKE_CHANGED,
                event
        );
        rabbitTemplate.convertAndSend(
                InteractionCommentMqConfig.EXCHANGE,
                InteractionCommentMqConfig.RK_COMMENT_LIKE_CHANGED,
                event
        );

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            CommentPO root = commentDao.selectBriefById(rootCommentId);
            assertThat(root).isNotNull();
            assertThat(root.getLikeCount()).isEqualTo(3L);
            assertThat(root.getReplyCount()).isEqualTo(0L);
            assertThat(commentHotRankRepository.topIds(postId, 10)).containsExactly(rootCommentId);
            assertThat(readCommentSnapshot(rootCommentId)).containsExactly(3L, 0L);
        });
    }

    @Test
    void rootReplyCountChangedEvent_shouldUpdateRootCommentAndRemainIdempotent() {
        long postId = uniqueId();
        long rootCommentId = uniqueId();
        insertRootComment(postId, rootCommentId);
        commentHotRankRepository.clear(postId);

        RootReplyCountChangedEvent event = new RootReplyCountChangedEvent();
        event.setEventId("reply-" + uniqueUuid());
        event.setPostId(postId);
        event.setRootCommentId(rootCommentId);
        event.setDelta(2L);
        event.setTsMs(System.currentTimeMillis());

        rabbitTemplate.convertAndSend(
                InteractionCommentMqConfig.EXCHANGE,
                InteractionCommentMqConfig.RK_REPLY_COUNT_CHANGED,
                event
        );
        rabbitTemplate.convertAndSend(
                InteractionCommentMqConfig.EXCHANGE,
                InteractionCommentMqConfig.RK_REPLY_COUNT_CHANGED,
                event
        );

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            CommentPO root = commentDao.selectBriefById(rootCommentId);
            assertThat(root).isNotNull();
            assertThat(root.getReplyCount()).isEqualTo(2L);
            assertThat(root.getLikeCount()).isEqualTo(0L);
            assertThat(commentHotRankRepository.topIds(postId, 10)).containsExactly(rootCommentId);
            assertThat(readCommentSnapshot(rootCommentId)).containsExactly(0L, 2L);
            assertThat(readReplyAggregationDelta(rootCommentId)).isEqualTo(2L);
        });
    }

    private long[] readCommentSnapshot(long rootCommentId) {
        String raw = stringRedisTemplate.opsForValue().get("count:comment:{" + rootCommentId + "}");
        return CountRedisCodec.decodeSlots(CountRedisCodec.fromRedisValue(raw), 2);
    }

    private long readReplyAggregationDelta(long rootCommentId) {
        Object raw = stringRedisTemplate.opsForHash().get("count:agg:{comment}:reply", String.valueOf(rootCommentId));
        if (raw == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(raw));
    }

    private void insertRootComment(long postId, long rootCommentId) {
        CommentPO root = new CommentPO();
        root.setCommentId(rootCommentId);
        root.setPostId(postId);
        root.setUserId(uniqueId());
        root.setRootId(null);
        root.setParentId(null);
        root.setReplyToId(null);
        root.setContentId(uniqueUuid());
        root.setStatus(1);
        root.setLikeCount(0L);
        root.setReplyCount(0L);
        root.setCreateTime(new Date());
        root.setUpdateTime(new Date());
        commentDao.insert(root);
    }
}
