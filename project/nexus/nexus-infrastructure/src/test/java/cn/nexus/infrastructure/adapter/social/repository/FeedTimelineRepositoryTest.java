package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.config.FeedInboxProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

class FeedTimelineRepositoryTest {

    @Test
    void replaceInbox_nonEmptySnapshotUsesAtomicBoundedLuaRebuild() {
        Fixture fixture = new Fixture();
        fixture.properties.setMaxSize(3);
        ReflectionTestUtils.setField(fixture.repository, "rebuildMergeWindowSize", 2);
        when(fixture.valueOperations.setIfAbsent(eq("feed:inbox:rebuild:lock:7"), eq("1"), eq(Duration.ofSeconds(30))))
                .thenReturn(Boolean.TRUE);

        fixture.repository.replaceInbox(7L, List.of(
                FeedInboxEntryVO.builder().postId(101L).publishTimeMs(1001L).build(),
                FeedInboxEntryVO.builder().postId(102L).publishTimeMs(1002L).build()));

        CapturedScript captured = fixture.captureScript();
        assertEquals(List.of("feed:inbox:7"), captured.keys());
        assertEquals("feed:inbox:7", captured.arg(0));
        assertTrue(captured.arg(1).startsWith("feed:inbox:tmp:7:"));
        assertEquals("2592000", captured.arg(2));
        assertEquals("3", captured.arg(3));
        assertEquals("2", captured.arg(4));
        assertEquals("__NOMORE__", captured.arg(5));
        assertEquals("2", captured.arg(6));
        assertEquals("101", captured.arg(7));
        assertEquals("1001", captured.arg(8));
        assertEquals("102", captured.arg(9));
        assertEquals("1002", captured.arg(10));
        assertTrue(captured.script().contains("ZREVRANGE"));
        assertFalse(captured.script().contains("ZRANGE', realKey, 0, -1"));
        verify(fixture.redisTemplate, never()).rename(any(), any());
        verify(fixture.redisTemplate, never()).expire(eq("feed:inbox:7"), any(Duration.class));
    }

    @Test
    void replaceInbox_emptySnapshotDoesNotMergeOldMembers() {
        Fixture fixture = new Fixture();
        when(fixture.valueOperations.setIfAbsent(eq("feed:inbox:rebuild:lock:8"), eq("1"), eq(Duration.ofSeconds(30))))
                .thenReturn(Boolean.TRUE);

        fixture.repository.replaceInbox(8L, List.of());

        CapturedScript captured = fixture.captureScript();
        assertEquals("0", captured.arg(6));
        assertEquals(7, captured.args().length);
        assertTrue(captured.script().contains("if entryCount > 0 and windowSize > 0 then"));
    }

    @Test
    void replaceInbox_lockMissSkipsRebuild() {
        Fixture fixture = new Fixture();
        when(fixture.valueOperations.setIfAbsent(eq("feed:inbox:rebuild:lock:9"), eq("1"), eq(Duration.ofSeconds(30))))
                .thenReturn(Boolean.FALSE);

        fixture.repository.replaceInbox(9L, List.of(FeedInboxEntryVO.builder().postId(1L).publishTimeMs(1L).build()));

        verify(fixture.redisTemplate, never()).execute(any(RedisScript.class), any(), Mockito.<Object[]>any());
    }

    private record CapturedScript(String script, List<String> keys, Object[] args) {
        private String arg(int index) {
            return String.valueOf(args[index]);
        }
    }

    private static final class Fixture {
        private final StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        private final FeedInboxProperties properties = new FeedInboxProperties();
        private final FeedTimelineRepository repository = new FeedTimelineRepository(redisTemplate, properties);

        private Fixture() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(redisTemplate.execute(Mockito.<RedisScript<Long>>any(), Mockito.<List<String>>any(), Mockito.<Object[]>any()))
                    .thenReturn(1L);
            properties.setMaxSize(1000);
            properties.setTtlDays(30);
            ReflectionTestUtils.setField(repository, "rebuildLockSeconds", 30);
        }

        private CapturedScript captureScript() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(redisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(), argsCaptor.capture());
            return new CapturedScript(scriptCaptor.getValue().getScriptAsString(), keysCaptor.getValue(), argsCaptor.getValue());
        }
    }
}
