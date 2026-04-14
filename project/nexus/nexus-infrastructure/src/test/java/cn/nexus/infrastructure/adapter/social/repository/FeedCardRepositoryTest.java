package cn.nexus.infrastructure.adapter.social.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.model.valobj.FeedCardBaseVO;
import cn.nexus.infrastructure.config.HotKeyStoreBridge;
import cn.nexus.infrastructure.config.SocialCacheHotTtlProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class FeedCardRepositoryTest {

    @Test
    void saveBatch_shouldSetValueWithTtlAtomically() throws Exception {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        ObjectMapper objectMapper = Mockito.mock(ObjectMapper.class);
        SocialCacheHotTtlProperties properties = new SocialCacheHotTtlProperties();
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        properties.setFeedCardSeconds(0L);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"postId\":1}");

        FeedCardRepository repository = new FeedCardRepository(stringRedisTemplate, objectMapper, properties, hotKeyStoreBridge);
        repository.saveBatch(List.of(FeedCardBaseVO.builder().postId(1L).authorId(2L).text("x").build()));

        verify(valueOperations).set(
                eq("feed:card:1"),
                eq("{\"postId\":1}"),
                anyLong(),
                eq(TimeUnit.SECONDS)
        );
        verify(stringRedisTemplate, never()).expire(eq("feed:card:1"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void getBatch_hotKeyShouldNotIssueSecondGetWhenExtendingTtl() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        SocialCacheHotTtlProperties properties = new SocialCacheHotTtlProperties();
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        properties.setFeedCardSeconds(7200L);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("feed:card:1")).thenReturn("{\"postId\":1,\"authorId\":2,\"text\":\"x\"}");
        when(stringRedisTemplate.getExpire("feed:card:1", TimeUnit.SECONDS)).thenReturn(120L);
        when(hotKeyStoreBridge.isHotKey("feed_card__1")).thenReturn(true);

        FeedCardRepository repository = new FeedCardRepository(stringRedisTemplate, new ObjectMapper(), properties, hotKeyStoreBridge);
        repository.getBatch(List.of(1L));

        verify(valueOperations, times(1)).get("feed:card:1");
        verify(stringRedisTemplate).expire("feed:card:1", 7200L, TimeUnit.SECONDS);
    }
}
