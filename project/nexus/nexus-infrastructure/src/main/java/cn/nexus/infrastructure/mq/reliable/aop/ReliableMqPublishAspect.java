package cn.nexus.infrastructure.mq.reliable.aop;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(ReliableMqAopOrder.PUBLISH_ASPECT_ORDER)
@RequiredArgsConstructor
public class ReliableMqPublishAspect {

    private final ReliableMqOutboxService outboxService;
    private final ReliableMqExpressionEvaluator expressionEvaluator;

    @Around("@annotation(annotation)")
    public Object around(ProceedingJoinPoint joinPoint, ReliableMqPublish annotation) throws Throwable {
        // Producer validation must run first; metadata is evaluated only after a successful method body.
        Object result = joinPoint.proceed();
        String eventId = expressionEvaluator.requiredString(joinPoint, annotation.eventId(), "eventId");
        Object payload = expressionEvaluator.requiredObject(joinPoint, annotation.payload(), "payload");
        outboxService.save(eventId, annotation.exchange(), annotation.routingKey(), payload);
        return result;
    }
}
