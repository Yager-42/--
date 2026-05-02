package cn.nexus.infrastructure.mq.reliable.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReliableMqConsume {

    String consumerName();

    String eventId();

    String payload();
}
