package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import cn.nexus.infrastructure.dao.social.ICommentDao;
import cn.nexus.infrastructure.dao.social.po.CommentPO;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import java.nio.charset.StandardCharsets;
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
            assertThat(commentHotRankRepository.topIds(postId, 10)).containsExactly(rootCommentId);
            assertThat(readCommentSnapshot(rootCommentId)).containsExactly(3L, 0L);
        });
    }

    private long[] readCommentSnapshot(long rootCommentId) {
        String key = "count:comment:{" + rootCommentId + "}";
        byte[] raw = stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<byte[]>) connection -> {
            if (connection == null || connection.stringCommands() == null) {
                return null;
            }
            return connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8));
        });
        return CountRedisCodec.decodeSlots(CountRedisCodec.fromRedisValue(raw), 2);
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
        root.setCreateTime(new Date());
        root.setUpdateTime(new Date());
        commentDao.insert(root);
    }
}
