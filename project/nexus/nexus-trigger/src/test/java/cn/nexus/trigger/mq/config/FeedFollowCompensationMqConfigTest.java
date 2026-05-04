package cn.nexus.trigger.mq.config;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedFollowCompensationMqConfigTest {

    @Test
    void followFeedCompensationQueueBindsToRelationFollowRoutingAndDlx() {
        RelationMqConfig relationMqConfig = new RelationMqConfig();
        FeedFollowCompensationMqConfig config = new FeedFollowCompensationMqConfig();

        DirectExchange relationExchange = relationMqConfig.relationExchange();
        DirectExchange relationDlxExchange = relationMqConfig.relationDlxExchange();
        Queue queue = config.followFeedCompensationQueue();
        Queue dlq = config.followFeedCompensationDlqQueue();
        Binding binding = config.followFeedCompensationBinding(queue, relationExchange);
        Binding dlqBinding = config.followFeedCompensationDlqBinding(dlq, relationDlxExchange);

        assertEquals("relation.counter.follow.feed.compensate.queue", queue.getName());
        assertEquals("relation.counter.follow.feed.compensate.dlq.queue", dlq.getName());
        assertEquals(RelationCounterRouting.EXCHANGE, binding.getExchange());
        assertEquals(RelationCounterRouting.RK_FOLLOW, binding.getRoutingKey());
        assertEquals(RelationCounterRouting.DLX_EXCHANGE, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(
                RelationCounterRouting.RK_FOLLOW_FEED_COMPENSATE_DLX,
                queue.getArguments().get("x-dead-letter-routing-key"));
        assertEquals(RelationCounterRouting.DLX_EXCHANGE, dlqBinding.getExchange());
        assertEquals(RelationCounterRouting.RK_FOLLOW_FEED_COMPENSATE_DLX, dlqBinding.getRoutingKey());
    }
}
