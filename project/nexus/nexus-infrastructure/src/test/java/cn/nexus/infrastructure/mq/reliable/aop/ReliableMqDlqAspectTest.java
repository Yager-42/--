package cn.nexus.infrastructure.mq.reliable.aop;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.mq.reliable.ReliableMqReplayService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class ReliableMqDlqAspectTest {

    private final ReliableMqReplayService replayService = Mockito.mock(ReliableMqReplayService.class);
    private final ReliableMqDlqAspect aspect = new ReliableMqDlqAspect(
            replayService,
            new ReliableMqExpressionEvaluator(new ObjectMapper()));

    @Test
    void around_shouldRecordFailureBeforeInvokingExplicitAlertingBody() throws Throwable {
        Message message = message("evt-1", "broker failed");
        Method method = DlqFixture.class.getMethod("handleDlq", Message.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new DlqFixture(), new Object[] {message});

        aspect.around(joinPoint, method.getAnnotation(ReliableMqDlq.class));

        verify(replayService).recordFailure("comment-consumer", "comment.queue", "social.interaction",
                "comment.created", message, ReliableMqDlqAspectTest.class.getName(), "evt-1", "broker failed");
        verify(joinPoint).proceed();
    }

    @Test
    void around_shouldUseOriginalRouteAsReplayTargetInsteadOfDlqQueue() throws Throwable {
        Message message = message("evt-1", "broker failed");
        message.getMessageProperties().setConsumerQueue("comment.queue.dlq");
        Method method = DlqFixture.class.getMethod("handleDlq", Message.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new DlqFixture(), new Object[] {message});

        aspect.around(joinPoint, method.getAnnotation(ReliableMqDlq.class));

        verify(replayService).recordFailure(Mockito.eq("comment-consumer"), Mockito.eq("comment.queue"),
                Mockito.eq("social.interaction"), Mockito.eq("comment.created"), Mockito.same(message),
                Mockito.eq(ReliableMqDlqAspectTest.class.getName()), Mockito.eq("evt-1"), Mockito.eq("broker failed"));
    }

    @Test
    void around_shouldNotInvokeAlertingBodyWhenDurableRecordingFails() throws Throwable {
        Message message = message("evt-1", "broker failed");
        Method method = DlqFixture.class.getMethod("handleDlq", Message.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new DlqFixture(), new Object[] {message});
        RuntimeException failure = new RuntimeException("record failed");
        Mockito.doThrow(failure).when(replayService).recordFailure(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.same(message), Mockito.any(), Mockito.any(), Mockito.any());

        assertThrows(RuntimeException.class,
                () -> aspect.around(joinPoint, method.getAnnotation(ReliableMqDlq.class)));

        verify(joinPoint, never()).proceed();
    }

    private Message message(String eventId, String lastError) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("eventId", eventId);
        properties.setHeader("x-exception-message", lastError);
        return new Message("{\"eventId\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8), properties);
    }

    private ProceedingJoinPoint joinPoint(Method method, Object target, Object[] args) throws Throwable {
        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[] {"message"});
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.proceed()).thenReturn(null);
        return joinPoint;
    }

    static class DlqFixture {
        @ReliableMqDlq(consumerName = "comment-consumer",
                originalQueue = "comment.queue",
                originalExchange = "social.interaction",
                originalRoutingKey = "comment.created",
                fallbackPayloadType = "cn.nexus.infrastructure.mq.reliable.aop.ReliableMqDlqAspectTest",
                eventId = "#message.messageProperties.headers['eventId']",
                lastError = "#message.messageProperties.headers['x-exception-message']")
        public void handleDlq(Message message) {
        }
    }
}
