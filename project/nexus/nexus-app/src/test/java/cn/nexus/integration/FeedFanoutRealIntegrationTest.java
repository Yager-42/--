package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.event.PostPublishedEvent;
import java.util.Date;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FeedFanoutRealIntegrationTest extends RealMiddlewareIntegrationTestSupport {

    @Test
    void postPublishedEvent_shouldFanoutToFollowersAndWriteFeedRedisViews() {
        long authorId = uniqueId();
        long followerA = uniqueId();
        long followerB = uniqueId();
        long postId = uniqueId();
        long publishTimeMs = System.currentTimeMillis();

        clearFeedKeys(authorId, authorId, followerA, followerB);
        deleteRedisKey("feed:global:latest");
        deleteRedisHashField("feed:author:category", String.valueOf(authorId));

        feedTimelineRepository.replaceInbox(followerA, java.util.List.of());
        feedTimelineRepository.replaceInbox(followerB, java.util.List.of());

        relationRepository.saveFollower(uniqueId(), authorId, followerA, new Date(publishTimeMs));
        relationRepository.saveFollower(uniqueId(), authorId, followerB, new Date(publishTimeMs));

        PostPublishedEvent event = new PostPublishedEvent();
        event.setAuthorId(authorId);
        event.setPostId(postId);
        event.setPublishTimeMs(publishTimeMs);

        rabbitTemplate.convertAndSend(FeedFanoutConfig.EXCHANGE, FeedFanoutConfig.ROUTING_KEY, event);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(feedOutboxRepository.pageOutbox(authorId, null, null, 10))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
            assertThat(feedGlobalLatestRepository.pageLatest(null, null, 10))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
            assertThat(inboxEntries(authorId, 10))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
            assertThat(inboxEntries(followerA, 10))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
            assertThat(inboxEntries(followerB, 10))
                    .extracting(item -> item.getPostId())
                    .contains(postId);
        });
    }
}
