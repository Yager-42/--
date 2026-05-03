package cn.nexus.trigger.mq.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

class FeedFanoutConfigTest {

    @Test
    void indexCleanupQueuesBindUpdatedAndDeletedSeparately() {
        FeedFanoutConfig config = new FeedFanoutConfig();
        DirectExchange feedExchange = config.feedExchange();
        DirectExchange dlxExchange = config.feedDlxExchange();

        Queue updatedQueue = config.feedIndexCleanupUpdatedQueue();
        Queue deletedQueue = config.feedIndexCleanupDeletedQueue();
        Queue updatedDlq = config.feedIndexCleanupUpdatedDlqQueue();
        Queue deletedDlq = config.feedIndexCleanupDeletedDlqQueue();
        Binding updatedBinding = config.feedIndexCleanupUpdatedBinding(updatedQueue, feedExchange);
        Binding deletedBinding = config.feedIndexCleanupDeletedBinding(deletedQueue, feedExchange);
        Binding updatedDlqBinding = config.feedIndexCleanupUpdatedDlqBinding(updatedDlq, dlxExchange);
        Binding deletedDlqBinding = config.feedIndexCleanupDeletedDlqBinding(deletedDlq, dlxExchange);

        assertEquals("feed.index.cleanup.updated.queue", updatedQueue.getName());
        assertEquals("feed.index.cleanup.deleted.queue", deletedQueue.getName());
        assertEquals("post.updated", updatedBinding.getRoutingKey());
        assertEquals("post.deleted", deletedBinding.getRoutingKey());
        assertEquals("feed.index.cleanup.updated.dlx", updatedQueue.getArguments().get("x-dead-letter-routing-key"));
        assertEquals("feed.index.cleanup.deleted.dlx", deletedQueue.getArguments().get("x-dead-letter-routing-key"));
        assertEquals("feed.index.cleanup.updated.dlq.queue", updatedDlq.getName());
        assertEquals("feed.index.cleanup.deleted.dlq.queue", deletedDlq.getName());
        assertEquals("feed.index.cleanup.updated.dlx", updatedDlqBinding.getRoutingKey());
        assertEquals("feed.index.cleanup.deleted.dlx", deletedDlqBinding.getRoutingKey());
    }
}
