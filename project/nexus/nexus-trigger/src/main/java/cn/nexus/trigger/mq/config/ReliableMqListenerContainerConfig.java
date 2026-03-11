package cn.nexus.trigger.mq.config;

import cn.nexus.infrastructure.mq.reliable.ReliableMqPolicy;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * 统一 RabbitMQ 可靠消费容器：
 * 1. 所有整改链路共用同一套瞬时重试参数。
 * 2. 超过上限后 reject，让队列自动进入 DLQ。
 */
@Configuration
public class ReliableMqListenerContainerConfig {

    @Bean(name = "reliableMqListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory reliableMqListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                                   MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setDefaultRequeueRejected(false);

        RetryOperationsInterceptor interceptor = RetryInterceptorBuilder.stateless()
                .maxAttempts(ReliableMqPolicy.CONSUMER_MAX_ATTEMPTS)
                .backOffOptions(
                        ReliableMqPolicy.CONSUMER_INITIAL_INTERVAL_MS,
                        ReliableMqPolicy.CONSUMER_MULTIPLIER,
                        ReliableMqPolicy.CONSUMER_MAX_INTERVAL_MS)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
        factory.setAdviceChain(interceptor);
        return factory;
    }
}
