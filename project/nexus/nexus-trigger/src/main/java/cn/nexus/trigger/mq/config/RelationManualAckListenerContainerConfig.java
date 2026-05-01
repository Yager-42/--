package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 关系计数投影消费容器：固定手动 ack。
 */
@Configuration
public class RelationManualAckListenerContainerConfig {

    @Bean(name = "relationManualAckListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory relationManualAckListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                                          MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(50);
        return factory;
    }
}
