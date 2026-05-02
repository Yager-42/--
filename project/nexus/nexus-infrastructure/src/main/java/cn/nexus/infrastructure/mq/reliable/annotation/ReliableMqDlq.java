package cn.nexus.infrastructure.mq.reliable.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReliableMqDlq {

    String consumerName();

    String originalQueue();

    String originalExchange();

    String originalRoutingKey();

    String fallbackPayloadType();

    String eventId() default "";

    String lastError() default "";
}
