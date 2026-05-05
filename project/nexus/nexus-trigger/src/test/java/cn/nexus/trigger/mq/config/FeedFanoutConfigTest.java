package cn.nexus.trigger.mq.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

class FeedFanoutConfigTest {

    @Test
    void indexCleanupQueueBindsUpdatedAndDeletedToSameQueue() {
        FeedFanoutConfig config = new FeedFanoutConfig();
        DirectExchange feedExchange = config.feedExchange();
        DirectExchange dlxExchange = config.feedDlxExchange();

        Queue cleanupQueue = config.feedIndexCleanupQueue();
        Queue cleanupDlq = config.feedIndexCleanupDlqQueue();
        Binding updatedBinding = config.feedIndexCleanupUpdatedBinding(cleanupQueue, feedExchange);
        Binding deletedBinding = config.feedIndexCleanupDeletedBinding(cleanupQueue, feedExchange);
        Binding cleanupDlqBinding = config.feedIndexCleanupDlqBinding(cleanupDlq, dlxExchange);

        assertEquals("feed.index.cleanup.queue", FeedFanoutConfig.Q_FEED_INDEX_CLEANUP);
        assertEquals("feed.index.cleanup.dlq.queue", FeedFanoutConfig.DLQ_FEED_INDEX_CLEANUP);
        assertEquals("feed.index.cleanup.queue", cleanupQueue.getName());
        assertEquals(cleanupQueue.getName(), updatedBinding.getDestination());
        assertEquals(cleanupQueue.getName(), deletedBinding.getDestination());
        assertEquals("post.updated", updatedBinding.getRoutingKey());
        assertEquals("post.deleted", deletedBinding.getRoutingKey());
        assertEquals("feed.index.cleanup.dlx", cleanupQueue.getArguments().get("x-dead-letter-routing-key"));
        assertEquals("feed.index.cleanup.dlq.queue", cleanupDlq.getName());
        assertEquals("feed.index.cleanup.dlx", cleanupDlqBinding.getRoutingKey());
    }

    @Test
    void oldIndexCleanupQueueConstantsAreRemoved() {
        assertThrows(NoSuchFieldException.class,
                () -> FeedFanoutConfig.class.getField("Q_FEED_INDEX_CLEANUP_UPDATED"));
        assertThrows(NoSuchFieldException.class,
                () -> FeedFanoutConfig.class.getField("Q_FEED_INDEX_CLEANUP_DELETED"));
        assertThrows(NoSuchFieldException.class,
                () -> FeedFanoutConfig.class.getField("DLQ_FEED_INDEX_CLEANUP_UPDATED"));
        assertThrows(NoSuchFieldException.class,
                () -> FeedFanoutConfig.class.getField("DLQ_FEED_INDEX_CLEANUP_DELETED"));
    }
}
