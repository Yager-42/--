package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.service.IContentService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqReplayService;
import cn.nexus.trigger.mq.config.ContentScheduleDelayConfig;
import cn.nexus.trigger.mq.event.ContentScheduleTriggerEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.Mockito.verify;

class ContentScheduleDLQConsumerTest {

    @Test
    void onDLQ_recordsDlqWithOriginalContentScheduleDelayRouteMetadata() throws Throwable {
        ReliableMqReplayService replayService = Mockito.mock(ReliableMqReplayService.class);
        IContentService contentService = Mockito.mock(IContentService.class);
        ContentScheduleDLQConsumer consumer = new ContentScheduleDLQConsumer(contentService, new ObjectMapper());
        Message message = new Message("{\"taskId\":42}".getBytes(), new MessageProperties());
        ProceedingJoinPoint joinPoint = ReliableDlqAspectTestSupport.joinPoint(consumer, "onDLQ", message);

        ReliableDlqAspectTestSupport.aspect(replayService).around(
                joinPoint,
                ReliableDlqAspectTestSupport.annotation(ContentScheduleDLQConsumer.class, "onDLQ"));

        verify(replayService).recordFailure(
                "ContentScheduleConsumer",
                ContentScheduleDelayConfig.QUEUE,
                ContentScheduleDelayConfig.EXCHANGE,
                ContentScheduleDelayConfig.ROUTING_KEY,
                message,
                ContentScheduleTriggerEvent.class.getName(),
                "",
                "content schedule dead-lettered");
    }
}
