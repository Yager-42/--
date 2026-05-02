package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqConsumeAspect;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqExpressionEvaluator;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.mockito.Mockito;

import static org.mockito.Mockito.when;

final class ReliableConsumerAspectTestSupport {

    private ReliableConsumerAspectTestSupport() {
    }

    static ReliableMqConsumeAspect aspect(ReliableMqConsumerRecordService consumerRecordService) {
        return new ReliableMqConsumeAspect(
                consumerRecordService,
                new ReliableMqExpressionEvaluator(),
                new ObjectMapper().findAndRegisterModules());
    }

    static ReliableMqConsume annotation(Class<?> consumerClass, String methodName, Class<?> parameterType) throws NoSuchMethodException {
        return method(consumerClass, methodName, parameterType).getAnnotation(ReliableMqConsume.class);
    }

    static ProceedingJoinPoint joinPoint(Object target, String methodName, String parameterName, Object arg, Runnable invocation)
            throws Throwable {
        Method method = method(target.getClass(), methodName, arg.getClass());
        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[] {parameterName});
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getArgs()).thenReturn(new Object[] {arg});
        when(joinPoint.proceed()).thenAnswer(invocationOnMock -> {
            invocation.run();
            return null;
        });
        return joinPoint;
    }

    private static Method method(Class<?> consumerClass, String methodName, Class<?> parameterType) throws NoSuchMethodException {
        return consumerClass.getMethod(methodName, parameterType);
    }
}
