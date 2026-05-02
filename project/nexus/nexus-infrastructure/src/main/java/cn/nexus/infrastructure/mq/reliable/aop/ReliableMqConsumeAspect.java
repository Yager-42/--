package cn.nexus.infrastructure.mq.reliable.aop;

import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(ReliableMqAopOrder.CONSUME_ASPECT_ORDER)
@RequiredArgsConstructor
public class ReliableMqConsumeAspect {

    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ReliableMqExpressionEvaluator expressionEvaluator;
    private final ObjectMapper objectMapper;

    @Around("@annotation(annotation)")
    public Object around(ProceedingJoinPoint joinPoint, ReliableMqConsume annotation) throws Throwable {
        String eventId = requiredEventId(joinPoint, annotation);
        Object payload = requiredPayload(joinPoint, annotation);
        String payloadJson = toJson(payload);
        StartResult startResult = consumerRecordService.startManual(eventId, annotation.consumerName(), payloadJson);
        if (startResult == StartResult.DUPLICATE_DONE) {
            return null;
        }
        if (startResult == StartResult.IN_PROGRESS) {
            throw new ImmediateRequeueAmqpException(
                    "requeue in-progress reliable mq consume eventId=" + eventId
                            + ", consumer=" + annotation.consumerName());
        }
        if (startResult == StartResult.INVALID) {
            throw new ReliableMqPermanentFailureException(
                    "invalid reliable mq consume metadata eventId=" + eventId + ", consumer=" + annotation.consumerName());
        }
        try {
            Object result = joinPoint.proceed();
            consumerRecordService.markDone(eventId, annotation.consumerName());
            return result;
        } catch (Throwable throwable) {
            consumerRecordService.markFail(eventId, annotation.consumerName(), throwable.getMessage());
            throw throwable;
        }
    }

    private String requiredEventId(ProceedingJoinPoint joinPoint, ReliableMqConsume annotation) {
        try {
            return expressionEvaluator.requiredString(joinPoint, annotation.eventId(), "eventId");
        } catch (RuntimeException e) {
            throw new ReliableMqPermanentFailureException(
                    "invalid reliable mq consume eventId, consumer=" + annotation.consumerName(), e);
        }
    }

    private Object requiredPayload(ProceedingJoinPoint joinPoint, ReliableMqConsume annotation) {
        try {
            return expressionEvaluator.requiredObject(joinPoint, annotation.payload(), "payload");
        } catch (RuntimeException e) {
            throw new ReliableMqPermanentFailureException(
                    "invalid reliable mq consume payload, consumer=" + annotation.consumerName(), e);
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("serialize reliable mq consume payload failed", e);
        }
    }
}
