package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.service.IContentService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.trigger.mq.event.ContentScheduleTriggerEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentScheduleConsumerTest {

    @Test
    void onMessage_lockContentionMarksFailAndRethrowsRetryableRuntimeException() throws Throwable {
        Fixture fixture = new Fixture();
        ContentScheduleTriggerEvent event = validEvent("evt-schedule-1");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-schedule-1"),
                Mockito.eq("ContentScheduleConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        when(fixture.stringRedisTemplate.opsForValue()).thenReturn(fixture.valueOperations);
        when(fixture.valueOperations.setIfAbsent(
                Mockito.eq("content:schedule:lock:42"),
                Mockito.anyString(),
                Mockito.eq(Duration.ofSeconds(60))))
                .thenReturn(false);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> fixture.invokeThroughAspect(event));

        assertEquals(IllegalStateException.class, thrown.getClass());
        assertEquals("content schedule lock exists", thrown.getMessage());
        verify(fixture.consumerRecordService).markFail("evt-schedule-1",
                "ContentScheduleConsumer", "content schedule lock exists");
        verify(fixture.contentService, never()).executeSchedule(Mockito.any());
    }

    private static ContentScheduleTriggerEvent validEvent(String eventId) {
        ContentScheduleTriggerEvent event = new ContentScheduleTriggerEvent();
        event.setEventId(eventId);
        event.setTaskId(42L);
        return event;
    }

    private static final class Fixture {
        private final IContentService contentService = Mockito.mock(IContentService.class);
        private final StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        private final ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        private final ContentScheduleConsumer consumer = new ContentScheduleConsumer(contentService, stringRedisTemplate);

        private void invokeThroughAspect(ContentScheduleTriggerEvent event) throws Throwable {
            ProceedingJoinPoint joinPoint = ReliableConsumerAspectTestSupport.joinPoint(
                    consumer, "onMessage", "event", event, () -> consumer.onMessage(event));
            ReliableConsumerAspectTestSupport.aspect(consumerRecordService).around(
                    joinPoint,
                    ReliableConsumerAspectTestSupport.annotation(
                            ContentScheduleConsumer.class, "onMessage", ContentScheduleTriggerEvent.class));
        }
    }
}
