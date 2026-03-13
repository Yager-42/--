package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broadcast local cache evict.
 */
@Configuration
public class ContentCacheEvictConfig {

    public static final String EXCHANGE = "social.cache.evict";

    @Bean
    public FanoutExchange contentCacheEvictExchange() {
        return new FanoutExchange(EXCHANGE, true, false);
    }

    @Bean
    public AnonymousQueue contentCacheEvictQueue() {
        return new AnonymousQueue();
    }

    @Bean
    public Binding contentCacheEvictBinding(AnonymousQueue contentCacheEvictQueue, FanoutExchange contentCacheEvictExchange) {
        return BindingBuilder.bind(contentCacheEvictQueue).to(contentCacheEvictExchange);
    }
}
