package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.domain.social.model.valobj.FeedAuthorCategoryEnumVO;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.event.PostPublishedEvent;
import java.util.Date;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FeedFanoutRealIntegrationTest extends RealMiddlewareIntegrationTestSupport {

    @Test
    void normalAuthorPostPublishedEvent_shouldWriteAuthorTimelineAndFanoutToOnlineFollowers() {
        long authorId = uniqueId();
        long followerA = uniqueId();
        long followerB = uniqueId();
        long postId = uniqueId();
        long publishTimeMs = System.currentTimeMillis();

        clearFeedKeys(authorId, authorId, followerA, followerB);
        deleteRedisHashField("feed:author:category", String.valueOf(authorId));

        long followerAMarkerPostId = createOnlineInboxMarker(followerA);
        long followerBMarkerPostId = createOnlineInboxMarker(followerB);

        relationRepository.saveFollower(uniqueId(), authorId, followerA, new Date(publishTimeMs));
        relationRepository.saveFollower(uniqueId(), authorId, followerB, new Date(publishTimeMs));

        PostPublishedEvent event = new PostPublishedEvent();
        event.setEventId("feed-fanout-normal-it-" + uniqueUuid());
        event.setAuthorId(authorId);
        event.setPostId(postId);
        event.setPublishTimeMs(publishTimeMs);

        rabbitTemplate.convertAndSend(FeedFanoutConfig.EXCHANGE, FeedFanoutConfig.ROUTING_KEY, event);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(reliableConsumerStatus(event.getEventId(), "FeedFanoutDispatcherConsumer"))
                    .isEqualTo("DONE");
            publishPendingReliableMqMessages();
            assertThat(feedAuthorTimelineRepository.pageTimeline(authorId, null, null, 10))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
            assertThat(inboxEntries(followerA, 10))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
            assertThat(inboxEntries(followerB, 10))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
            assertThat(inboxEntries(authorId, 10))
                    .extracting(item -> item.getPostId())
                    .doesNotContain(postId);
        });

        removeOnlineInboxMarker(followerA, followerAMarkerPostId);
        removeOnlineInboxMarker(followerB, followerBMarkerPostId);
    }

    @Test
    void bigvAuthorPostPublishedEvent_shouldWriteAuthorTimelineWithoutFollowerFanout() {
        long authorId = uniqueId();
        long followerId = uniqueId();
        long postId = uniqueId();
        long publishTimeMs = System.currentTimeMillis();

        clearFeedKeys(authorId, authorId, followerId);
        deleteRedisHashField("feed:author:category", String.valueOf(authorId));
        stringRedisTemplate.opsForHash().put(
                "feed:author:category",
                String.valueOf(authorId),
                String.valueOf(FeedAuthorCategoryEnumVO.BIGV.getCode())
        );

        long followerMarkerPostId = createOnlineInboxMarker(followerId);
        relationRepository.saveFollower(uniqueId(), authorId, followerId, new Date(publishTimeMs));

        PostPublishedEvent event = new PostPublishedEvent();
        event.setEventId("feed-fanout-bigv-it-" + uniqueUuid());
        event.setAuthorId(authorId);
        event.setPostId(postId);
        event.setPublishTimeMs(publishTimeMs);

        rabbitTemplate.convertAndSend(FeedFanoutConfig.EXCHANGE, FeedFanoutConfig.ROUTING_KEY, event);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(reliableConsumerStatus(event.getEventId(), "FeedFanoutDispatcherConsumer"))
                    .isEqualTo("DONE");
            publishPendingReliableMqMessages();
            assertThat(feedAuthorTimelineRepository.pageTimeline(authorId, null, null, 10))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
        });
        assertThat(reliableConsumerStatus(event.getEventId() + ":0:200", "FeedFanoutTaskConsumer"))
                .isNull();
        assertThat(inboxEntries(followerId, 10))
                .extracting(item -> item.getPostId())
                .doesNotContain(postId);
        assertThat(inboxEntries(authorId, 10))
                .extracting(item -> item.getPostId())
                .doesNotContain(postId);

        removeOnlineInboxMarker(followerId, followerMarkerPostId);
    }
}
