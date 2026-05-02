package cn.nexus.trigger.cache;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.nexus.infrastructure.adapter.social.repository.ContentRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardRepository;
import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqExpressionEvaluator;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqPublishAspect;
import cn.nexus.trigger.http.social.support.ContentDetailQueryService;
import cn.nexus.trigger.mq.config.ContentCacheEvictConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(ContentCacheEvictPortTest.TestConfig.class)
class ContentCacheEvictPortTest {

    @Autowired
    private ContentCacheEvictPort port;

    @Autowired
    private ReliableMqOutboxService reliableMqOutboxService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private ContentDetailQueryService contentDetailQueryService;

    @Autowired
    private FeedCardRepository feedCardRepository;

    @BeforeEach
    void setUp() {
        Mockito.reset(reliableMqOutboxService, stringRedisTemplate, contentRepository, contentDetailQueryService,
                feedCardRepository);
    }

    @Test
    void evictPost_shouldDeleteLocalAgainDuringDelayedDoubleDelete() throws Exception {
        port.evictPost(123L);
        Thread.sleep(1400L);

        verify(contentRepository, times(2)).evictLocalPostCache(123L);
        verify(contentDetailQueryService, times(2)).evictLocal(123L);
        verify(feedCardRepository, times(2)).evictLocal(123L);
        verify(stringRedisTemplate, times(2)).delete("interact:content:post:123");
        verify(feedCardRepository, times(2)).evictRedis(123L);
        verify(reliableMqOutboxService).save(anyString(), anyString(), anyString(), any());
    }

    @Test
    void evictPost_shouldSaveOutboxThroughReliableMqPublishAspect() {
        port.evictPost(456L);

        org.junit.jupiter.api.Assertions.assertTrue(AopUtils.isAopProxy(port));
        ArgumentCaptor<String> eventIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ContentCacheEvictEvent> payloadCaptor = ArgumentCaptor.forClass(ContentCacheEvictEvent.class);
        verify(reliableMqOutboxService).save(eventIdCaptor.capture(), Mockito.eq(ContentCacheEvictConfig.EXCHANGE),
                Mockito.eq(""), payloadCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(456L, payloadCaptor.getValue().getPostId());
        org.junit.jupiter.api.Assertions.assertEquals(payloadCaptor.getValue().getEventId(), eventIdCaptor.getValue());
    }

    @Test
    void evictPost_shouldKeepExistingNullPostNoopValidation() {
        port.evictPost(null);

        verifyNoInteractions(reliableMqOutboxService);
    }

    @Configuration
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(ContentCacheEvictPort.class)
    static class TestConfig {

        @Bean
        ReliableMqPublishAspect reliableMqPublishAspect(ReliableMqOutboxService outboxService,
                                                        ReliableMqExpressionEvaluator evaluator) {
            return new ReliableMqPublishAspect(outboxService, evaluator);
        }

        @Bean
        ReliableMqExpressionEvaluator reliableMqExpressionEvaluator() {
            return new ReliableMqExpressionEvaluator();
        }

        @Bean
        ReliableMqOutboxService reliableMqOutboxService() {
            return org.mockito.Mockito.mock(ReliableMqOutboxService.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return org.mockito.Mockito.mock(StringRedisTemplate.class);
        }

        @Bean
        ContentRepository contentRepository() {
            return org.mockito.Mockito.mock(ContentRepository.class);
        }

        @Bean
        ContentDetailQueryService contentDetailQueryService() {
            return org.mockito.Mockito.mock(ContentDetailQueryService.class);
        }

        @Bean
        FeedCardRepository feedCardRepository() {
            return org.mockito.Mockito.mock(FeedCardRepository.class);
        }
    }
}
