package cn.nexus.infrastructure.mq.reliable.aop;

import cn.nexus.infrastructure.mq.reliable.ReliableMqReplayService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.amqp.core.Message;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class ReliableMqDlqAspect {

    private final ReliableMqReplayService replayService;
    private final ReliableMqExpressionEvaluator expressionEvaluator;

    @Around("@annotation(annotation)")
    public Object around(ProceedingJoinPoint joinPoint, ReliableMqDlq annotation) throws Throwable {
        Message message = findMessage(joinPoint.getArgs());
        String explicitEventId = expressionEvaluator.optionalString(joinPoint, annotation.eventId());
        String lastError = expressionEvaluator.optionalString(joinPoint, annotation.lastError());
        replayService.recordFailure(annotation.consumerName(), annotation.originalQueue(), annotation.originalExchange(),
                annotation.originalRoutingKey(), message, annotation.fallbackPayloadType(), explicitEventId, lastError);
        return joinPoint.proceed();
    }

    private Message findMessage(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Message message) {
                return message;
            }
        }
        throw new IllegalArgumentException("@ReliableMqDlq method must have an org.springframework.amqp.core.Message argument");
    }
}
