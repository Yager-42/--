package cn.nexus.trigger.mq.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * Like/Unlike consumers use batch-mode listener container:
 * - batchSize ~= 1000
 * - receiveTimeout ~= 1s
 *
 * This approximates playbook's BufferTrigger(1000/1s) while keeping MQ ack semantics safe.
 */
@Configuration
public class LikeUnlikeListenerContainerConfig {

    @Value("${mq.like-unlike.consumer.concurrency:1}")
    private int concurrency;

    @Value("${mq.like-unlike.consumer.prefetch:1000}")
    private int prefetch;

    @Value("${mq.like-unlike.consumer.batch-size:1000}")
    private int batchSize;

    @Value("${mq.like-unlike.consumer.receive-timeout-ms:1000}")
    private long receiveTimeoutMs;

    @Value("${mq.like-unlike.consumer.retry.max-attempts:5}")
    private int maxAttempts;

    @Value("${mq.like-unlike.consumer.retry.initial-interval-ms:200}")
    private long initialIntervalMs;

    @Value("${mq.like-unlike.consumer.retry.multiplier:2.0}")
    private double multiplier;

    @Value("${mq.like-unlike.consumer.retry.max-interval-ms:5000}")
    private long maxIntervalMs;

    @Bean(name = "likeUnlikeBatchListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory likeUnlikeBatchListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                                        MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);

        int c = Math.max(1, concurrency);
        factory.setConcurrentConsumers(c);
        factory.setMaxConcurrentConsumers(c);
        factory.setPrefetchCount(Math.max(1, prefetch));
        factory.setDefaultRequeueRejected(false);

        // Enable consumer-side batching (deliver List<Message> to @RabbitListener).
        factory.setConsumerBatchEnabled(true);
        factory.setBatchListener(true);
        factory.setBatchSize(Math.max(1, batchSize));
        factory.setReceiveTimeout(Math.max(1L, receiveTimeoutMs));

        RetryOperationsInterceptor interceptor = RetryInterceptorBuilder.stateless()
                .maxAttempts(Math.max(1, maxAttempts))
                .backOffOptions(
                        Math.max(1L, initialIntervalMs),
                        Math.max(1.0d, multiplier),
                        Math.max(1L, maxIntervalMs))
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
        factory.setAdviceChain(interceptor);
        return factory;
    }
}
