package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.ReliableMqReplayService;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.Mockito.verify;

class FeedFanoutDispatcherDlqConsumerTest {

    @Test
    void onMessage_recordsDlqWithOriginalFeedPostPublishedRouteMetadata() throws Throwable {
        ReliableMqReplayService replayService = Mockito.mock(ReliableMqReplayService.class);
        FeedFanoutDispatcherDlqConsumer consumer = new FeedFanoutDispatcherDlqConsumer();
        Message message = new Message("{}".getBytes(), new MessageProperties());
        ProceedingJoinPoint joinPoint = ReliableDlqAspectTestSupport.joinPoint(consumer, "onMessage", message);

        ReliableDlqAspectTestSupport.aspect(replayService).around(
                joinPoint,
                ReliableDlqAspectTestSupport.annotation(FeedFanoutDispatcherDlqConsumer.class, "onMessage"));

        verify(replayService).recordFailure(
                "FeedFanoutDispatcherConsumer",
                FeedFanoutConfig.QUEUE,
                FeedFanoutConfig.EXCHANGE,
                FeedFanoutConfig.ROUTING_KEY,
                message,
                "cn.nexus.types.event.PostPublishedEvent",
                "",
                "feed fanout dispatcher dead-lettered");
    }
}
