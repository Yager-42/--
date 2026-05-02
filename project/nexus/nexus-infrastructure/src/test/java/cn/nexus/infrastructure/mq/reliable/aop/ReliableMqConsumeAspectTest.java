package cn.nexus.infrastructure.mq.reliable.aop;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReliableMqConsumeAspectTest {

    private final ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
    private final ReliableMqConsumeAspect aspect = new ReliableMqConsumeAspect(
            consumerRecordService,
            new ReliableMqExpressionEvaluator(),
            new ObjectMapper());

    @Test
    void around_shouldShortCircuitDuplicateDoneWithoutInvokingBusinessMethod() throws Throwable {
        ConsumeEvent event = new ConsumeEvent("evt-1", "hello");
        Method method = ConsumeFixture.class.getMethod("consume", ConsumeEvent.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new ConsumeFixture(), new Object[] {event}, null);
        when(consumerRecordService.startManual(Mockito.eq("evt-1"), Mockito.eq("comment-consumer"), Mockito.anyString()))
                .thenReturn(StartResult.DUPLICATE_DONE);

        aspect.around(joinPoint, method.getAnnotation(ReliableMqConsume.class));

        verify(joinPoint, never()).proceed();
        verify(consumerRecordService, never()).markDone(Mockito.any(), Mockito.any());
    }

    @Test
    void around_shouldInvokeBusinessAndMarkDoneWhenStarted() throws Throwable {
        ConsumeEvent event = new ConsumeEvent("evt-1", "hello");
        Method method = ConsumeFixture.class.getMethod("consume", ConsumeEvent.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new ConsumeFixture(), new Object[] {event}, null);
        when(consumerRecordService.startManual(Mockito.eq("evt-1"), Mockito.eq("comment-consumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);

        aspect.around(joinPoint, method.getAnnotation(ReliableMqConsume.class));

        verify(joinPoint).proceed();
        verify(consumerRecordService).markDone("evt-1", "comment-consumer");
    }

    @Test
    void around_shouldMarkFailAndRethrowOriginalFailure() throws Throwable {
        ConsumeEvent event = new ConsumeEvent("evt-1", "hello");
        Method method = ConsumeFixture.class.getMethod("consume", ConsumeEvent.class);
        RuntimeException failure = new RuntimeException("boom");
        ProceedingJoinPoint joinPoint = joinPoint(method, new ConsumeFixture(), new Object[] {event}, failure);
        when(consumerRecordService.startManual(Mockito.eq("evt-1"), Mockito.eq("comment-consumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> aspect.around(joinPoint, method.getAnnotation(ReliableMqConsume.class)));

        assertSame(failure, thrown);
        verify(consumerRecordService).markFail("evt-1", "comment-consumer", "boom");
    }

    @Test
    void around_shouldRethrowPermanentFailureForInvalidStartResult() throws Throwable {
        ConsumeEvent event = new ConsumeEvent("evt-1", "hello");
        Method method = ConsumeFixture.class.getMethod("consume", ConsumeEvent.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new ConsumeFixture(), new Object[] {event}, null);
        when(consumerRecordService.startManual(Mockito.eq("evt-1"), Mockito.eq("comment-consumer"), Mockito.anyString()))
                .thenReturn(StartResult.INVALID);

        assertThrows(ReliableMqPermanentFailureException.class,
                () -> aspect.around(joinPoint, method.getAnnotation(ReliableMqConsume.class)));

        verify(joinPoint, never()).proceed();
        verify(consumerRecordService, never()).markDone(Mockito.any(), Mockito.any());
    }

    @Test
    void around_shouldRethrowPermanentFailureForBlankEventIdExpressionResult() throws Throwable {
        ConsumeEvent event = new ConsumeEvent(" ", "hello");
        Method method = ConsumeFixture.class.getMethod("consume", ConsumeEvent.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new ConsumeFixture(), new Object[] {event}, null);

        assertThrows(ReliableMqPermanentFailureException.class,
                () -> aspect.around(joinPoint, method.getAnnotation(ReliableMqConsume.class)));

        verify(joinPoint, never()).proceed();
        verify(consumerRecordService, never()).startManual(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void around_shouldRethrowPermanentFailureWhenEventIdExpressionCannotReadNullPayload() throws Throwable {
        Method method = ConsumeFixture.class.getMethod("consume", ConsumeEvent.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new ConsumeFixture(), new Object[] {null}, null);

        assertThrows(ReliableMqPermanentFailureException.class,
                () -> aspect.around(joinPoint, method.getAnnotation(ReliableMqConsume.class)));

        verify(joinPoint, never()).proceed();
        verify(consumerRecordService, never()).startManual(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void around_shouldRethrowBusinessPermanentFailureWithoutSwallowingIt() throws Throwable {
        ConsumeEvent event = new ConsumeEvent("evt-1", "hello");
        Method method = ConsumeFixture.class.getMethod("consume", ConsumeEvent.class);
        ReliableMqPermanentFailureException failure = new ReliableMqPermanentFailureException("bad payload");
        ProceedingJoinPoint joinPoint = joinPoint(method, new ConsumeFixture(), new Object[] {event}, failure);
        when(consumerRecordService.startManual(Mockito.eq("evt-1"), Mockito.eq("comment-consumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);

        ReliableMqPermanentFailureException thrown = assertThrows(ReliableMqPermanentFailureException.class,
                () -> aspect.around(joinPoint, method.getAnnotation(ReliableMqConsume.class)));

        assertSame(failure, thrown);
        verify(consumerRecordService).markFail("evt-1", "comment-consumer", "bad payload");
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

    static class ConsumeFixture {
        @ReliableMqConsume(consumerName = "comment-consumer", eventId = "#event.eventId", payload = "#event")
        public void consume(ConsumeEvent event) {
        }
    }

    record ConsumeEvent(String eventId, String body) {
    }
}
