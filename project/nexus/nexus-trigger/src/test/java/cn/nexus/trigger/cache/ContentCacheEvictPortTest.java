package cn.nexus.trigger.cache;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.nexus.infrastructure.adapter.social.repository.ContentRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardRepository;
import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.trigger.http.social.support.ContentDetailQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

class ContentCacheEvictPortTest {

    @Test
    void evictPost_shouldDeleteLocalAgainDuringDelayedDoubleDelete() throws Exception {
        ReliableMqOutboxService reliableMqOutboxService = Mockito.mock(ReliableMqOutboxService.class);
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        ContentRepository contentRepository = Mockito.mock(ContentRepository.class);
        ContentDetailQueryService contentDetailQueryService = Mockito.mock(ContentDetailQueryService.class);
        FeedCardRepository feedCardRepository = Mockito.mock(FeedCardRepository.class);

        ContentCacheEvictPort port = new ContentCacheEvictPort(
                reliableMqOutboxService,
                stringRedisTemplate,
                contentRepository,
                contentDetailQueryService,
                feedCardRepository
        );

        port.evictPost(123L);
        Thread.sleep(1400L);

        verify(contentRepository, times(2)).evictLocalPostCache(123L);
        verify(contentDetailQueryService, times(2)).evictLocal(123L);
        verify(feedCardRepository, times(2)).evictLocal(123L);
        verify(stringRedisTemplate, times(2)).delete("interact:content:post:123");
        verify(feedCardRepository, times(2)).evictRedis(123L);
        verify(reliableMqOutboxService).save(anyString(), anyString(), anyString(), any());
    }
}
