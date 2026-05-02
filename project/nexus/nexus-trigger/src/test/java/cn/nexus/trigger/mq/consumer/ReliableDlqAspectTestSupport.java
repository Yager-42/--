package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.ReliableMqReplayService;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqDlqAspect;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqExpressionEvaluator;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

final class ReliableDlqAspectTestSupport {

    private ReliableDlqAspectTestSupport() {
    }

    static ReliableMqDlqAspect aspect(ReliableMqReplayService replayService) {
        return new ReliableMqDlqAspect(replayService, new ReliableMqExpressionEvaluator());
    }

    static ReliableMqDlq annotation(Class<?> consumerClass, String methodName) throws NoSuchMethodException {
        ReliableMqDlq annotation = method(consumerClass, methodName).getAnnotation(ReliableMqDlq.class);
        assertNotNull(annotation, "DLQ listener should be annotated with @ReliableMqDlq");
        return annotation;
    }

    static ProceedingJoinPoint joinPoint(Object target, String methodName, Message message) throws Throwable {
        Method method = method(target.getClass(), methodName);
        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[] {"message"});
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getArgs()).thenReturn(new Object[] {message});
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            method.invoke(target, message);
            return null;
        });
        return joinPoint;
    }

    private static Method method(Class<?> consumerClass, String methodName) throws NoSuchMethodException {
        return consumerClass.getMethod(methodName, Message.class);
    }
}
