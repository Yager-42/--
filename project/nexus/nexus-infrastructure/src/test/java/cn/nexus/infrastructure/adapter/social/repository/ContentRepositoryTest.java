package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IContentCacheEvictPort;
import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.infrastructure.config.SocialCacheHotTtlProperties;
import cn.nexus.infrastructure.dao.social.IContentDraftDao;
import cn.nexus.infrastructure.dao.social.IContentHistoryDao;
import cn.nexus.infrastructure.dao.social.IContentPostDao;
import cn.nexus.infrastructure.dao.social.IContentPostTypeDao;
import cn.nexus.infrastructure.dao.social.IContentScheduleDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ContentRepositoryTest {

    @Test
    void findPost_shouldReturnNullWhenNullSentinelHit() {
        IContentPostDao contentPostDao = Mockito.mock(IContentPostDao.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        ContentRepository repository = newRepository(contentPostDao, valueOperations);
        when(valueOperations.get("interact:content:post:101")).thenReturn("NULL");

        ContentPostEntity result = repository.findPost(101L);

        assertNull(result);
        verify(contentPostDao, never()).selectById(anyLong());
    }

    @Test
    void findPost_shouldReadPositiveRedisCacheBeforeDb() throws Exception {
        IContentPostDao contentPostDao = Mockito.mock(IContentPostDao.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        ContentRepository repository = newRepository(contentPostDao, valueOperations);

        String json = new ObjectMapper().writeValueAsString(ContentPostEntity.builder()
                .postId(101L)
                .userId(11L)
                .title("cached")
                .contentText("body")
                .status(1)
                .build());
        when(valueOperations.get("interact:content:post:101")).thenReturn(json);

        ContentPostEntity result = repository.findPost(101L);

        assertEquals(101L, result.getPostId());
        assertEquals("body", result.getContentText());
        verify(contentPostDao, never()).selectById(anyLong());
    }

    @Test
    void listPostsByIds_shouldFilterNonPublishedEntriesFromSharedPostCache() throws Exception {
        IContentPostDao contentPostDao = Mockito.mock(IContentPostDao.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        ContentRepository repository = newRepository(contentPostDao, valueOperations);

        ObjectMapper objectMapper = new ObjectMapper();
        String draftJson = objectMapper.writeValueAsString(ContentPostEntity.builder()
                .postId(1L)
                .userId(10L)
                .status(1)
                .contentText("draft")
                .build());
        String publishedJson = objectMapper.writeValueAsString(ContentPostEntity.builder()
                .postId(2L)
                .userId(20L)
                .status(2)
                .contentText("published")
                .build());
        when(valueOperations.multiGet(List.of("interact:content:post:1", "interact:content:post:2")))
                .thenReturn(Arrays.asList(draftJson, publishedJson));

        List<ContentPostEntity> result = repository.listPostsByIds(List.of(1L, 2L));

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getPostId());
        verify(contentPostDao, never()).selectByIds(any());
    }

    @Test
    void listPostsByIds_shouldNotWriteNullForStatusScopedMiss() {
        IContentPostDao contentPostDao = Mockito.mock(IContentPostDao.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        ContentRepository repository = newRepository(contentPostDao, valueOperations);

        when(valueOperations.multiGet(List.of("interact:content:post:9")))
                .thenReturn(java.util.Arrays.asList((String) null));
        when(contentPostDao.selectByIds(List.of(9L))).thenReturn(List.of());

        List<ContentPostEntity> result = repository.listPostsByIds(List.of(9L));

        assertEquals(0, result.size());
        verify(valueOperations, never()).set(eq("interact:content:post:9"), eq("NULL"), anyLong(), eq(TimeUnit.SECONDS));
    }

    private ContentRepository newRepository(IContentPostDao contentPostDao, ValueOperations<String, String> valueOperations) {
        IContentDraftDao contentDraftDao = Mockito.mock(IContentDraftDao.class);
        IContentPostTypeDao contentPostTypeDao = Mockito.mock(IContentPostTypeDao.class);
        IContentHistoryDao contentHistoryDao = Mockito.mock(IContentHistoryDao.class);
        IContentScheduleDao contentScheduleDao = Mockito.mock(IContentScheduleDao.class);
        IPostContentKvPort postContentKvPort = Mockito.mock(IPostContentKvPort.class);
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IContentCacheEvictPort> provider = Mockito.mock(ObjectProvider.class);
        SocialCacheHotTtlProperties properties = new SocialCacheHotTtlProperties();
        properties.setContentPostSeconds(0L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        return new ContentRepository(
                contentDraftDao,
                contentPostDao,
                contentPostTypeDao,
                contentHistoryDao,
                contentScheduleDao,
                postContentKvPort,
                stringRedisTemplate,
                new ObjectMapper(),
                provider,
                properties
        );
    }
}
