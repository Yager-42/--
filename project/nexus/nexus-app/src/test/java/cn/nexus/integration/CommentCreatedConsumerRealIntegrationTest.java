package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.CommentCreatedEvent;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CommentCreatedConsumerRealIntegrationTest extends RealMiddlewareIntegrationTestSupport {

    @Test
    void commentCreatedEvent_shouldUpdateCommentHotRankInRedis() {
        long postId = uniqueId();
        long rootCommentId = uniqueId();

        commentHotRankRepository.clear(postId);

        CommentCreatedEvent event = new CommentCreatedEvent();
        event.setPostId(postId);
        event.setCommentId(rootCommentId);
        event.setRootId(null);
        event.setUserId(uniqueId());
        event.setCreateTimeMs(System.currentTimeMillis());

        rabbitTemplate.convertAndSend(
                InteractionCommentMqConfig.EXCHANGE,
                InteractionCommentMqConfig.RK_COMMENT_CREATED,
                event
        );

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(commentHotRankRepository.topIds(postId, 10)).contains(rootCommentId);
        });
    }
}
