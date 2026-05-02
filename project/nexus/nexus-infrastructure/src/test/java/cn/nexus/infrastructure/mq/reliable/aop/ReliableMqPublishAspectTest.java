package cn.nexus.infrastructure.mq.reliable.aop;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReliableMqPublishAspectTest {

    private final ReliableMqOutboxService outboxService = Mockito.mock(ReliableMqOutboxService.class);
    private final ReliableMqPublishAspect aspect = new ReliableMqPublishAspect(
            outboxService,
            new ReliableMqExpressionEvaluator(new ObjectMapper()));

    @Test
    void around_shouldSaveOutboxAfterSuccessfulMethod() throws Throwable {
        PublishFixture fixture = new PublishFixture();
        PublishEvent event = new PublishEvent("evt-1", "hello");
        Method method = PublishFixture.class.getMethod("publish", PublishEvent.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, fixture, new Object[] {event}, null);

        aspect.around(joinPoint, method.getAnnotation(ReliableMqPublish.class));

        verify(outboxService).save("evt-1", "social.interaction", "comment.created", event);
    }

    @Test
    void around_shouldRejectBlankEventId() throws Throwable {
        PublishFixture fixture = new PublishFixture();
        PublishEvent event = new PublishEvent(" ", "hello");
        Method method = PublishFixture.class.getMethod("publish", PublishEvent.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, fixture, new Object[] {event}, null);

        assertThrows(IllegalArgumentException.class,
                () -> aspect.around(joinPoint, method.getAnnotation(ReliableMqPublish.class)));
    }

    @Test
    void around_shouldNotSaveWhenBusinessMethodFails() throws Throwable {
        PublishFixture fixture = new PublishFixture();
        PublishEvent event = new PublishEvent("evt-1", "hello");
        Method method = PublishFixture.class.getMethod("publish", PublishEvent.class);
        RuntimeException failure = new RuntimeException("boom");
        ProceedingJoinPoint joinPoint = joinPoint(method, fixture, new Object[] {event}, failure);

        assertThrows(RuntimeException.class,
                () -> aspect.around(joinPoint, method.getAnnotation(ReliableMqPublish.class)));

        verify(outboxService, never()).save(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    private ProceedingJoinPoint joinPoint(Method method, Object target, Object[] args, Throwable failure) throws Throwable {
        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[] {"event"});
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getArgs()).thenReturn(args);
        if (failure == null) {
            when(joinPoint.proceed()).thenReturn(null);
        } else {
            when(joinPoint.proceed()).thenThrow(failure);
        }
        return joinPoint;
    }

    static class PublishFixture {
        @ReliableMqPublish(exchange = "social.interaction",
                routingKey = "comment.created",
                eventId = "#event.eventId",
                payload = "#event")
        public void publish(PublishEvent event) {
        }
    }

    record PublishEvent(String eventId, String body) {
    }
}
