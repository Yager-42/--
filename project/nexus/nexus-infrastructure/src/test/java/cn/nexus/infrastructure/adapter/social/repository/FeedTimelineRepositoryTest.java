package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.config.FeedInboxProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class FeedTimelineRepositoryTest {

    @Test
    void addToInbox_trimsByCardinality() {
        Fixture fixture = new Fixture();
        fixture.properties.setMaxSize(3);
        when(fixture.zSetOperations.zCard("feed:inbox:7")).thenReturn(4L);

        fixture.repository.addToInbox(7L, 101L, 1001L);

        verify(fixture.zSetOperations).add("feed:inbox:7", "101", 1001.0);
        verify(fixture.zSetOperations).removeRange("feed:inbox:7", 0L, 0L);
    }

    @Test
    void addToInbox_doesNotTrimWhenCardinalityIsWithinLimit() {
        Fixture fixture = new Fixture();
        fixture.properties.setMaxSize(3);
        when(fixture.zSetOperations.zCard("feed:inbox:7")).thenReturn(3L);

        fixture.repository.addToInbox(7L, 101L, 1001L);

        verify(fixture.zSetOperations, never()).removeRange(anyString(), anyLong(), anyLong());
    }

    @Test
    void pageInboxEntries_respectsMaxIdCursorAndSkipsMalformedMembers() {
        Fixture fixture = new Fixture();
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(tuple("201", 2000.0));
        tuples.add(tuple("200", 2000.0));
        tuples.add(tuple("bad", 1999.0));
        tuples.add(tuple("", 1998.0));
        tuples.add(tuple("199", 1997.0));
        when(fixture.zSetOperations.reverseRangeByScoreWithScores(
                eq("feed:inbox:7"), eq(0.0), eq(2000.0), eq(0L), eq(23L)))
                .thenReturn(tuples);

        List<FeedInboxEntryVO> result = fixture.repository.pageInboxEntries(7L, 2000L, 201L, 3);

        assertEquals(List.of(200L, 199L), result.stream().map(FeedInboxEntryVO::getPostId).toList());
        assertEquals(List.of(2000L, 1997L), result.stream().map(FeedInboxEntryVO::getPublishTimeMs).toList());
        verify(fixture.redisTemplate).expire("feed:inbox:7", Duration.ofDays(30));
    }

    @Test
    void pageInboxEntries_nullRedisResultReturnsEmpty() {
        Fixture fixture = new Fixture();
        when(fixture.zSetOperations.reverseRangeByScoreWithScores(
                eq("feed:inbox:7"), eq(0.0), eq((double) Long.MAX_VALUE), eq(0L), eq(21)))
                .thenReturn(null);

        List<FeedInboxEntryVO> result = fixture.repository.pageInboxEntries(7L, null, null, 1);

        assertTrue(result.isEmpty());
    }

    @Test
    void filterOnlineUsers_usesInboxKeyExistence() {
        Fixture fixture = new Fixture();
        when(fixture.redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RedisCallback<Object> callback = invocation.getArgument(0);
                    RedisConnection connection = Mockito.mock(RedisConnection.class);
                    RedisKeyCommands keyCommands = Mockito.mock(RedisKeyCommands.class);
                    when(connection.keyCommands()).thenReturn(keyCommands);
                    callback.doInRedis(connection);
                    verify(keyCommands).exists("feed:inbox:1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    verify(keyCommands).exists("feed:inbox:2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return List.of(Boolean.TRUE, Boolean.FALSE);
                });

        Set<Long> result = fixture.repository.filterOnlineUsers(Arrays.asList(1L, null, 2L));

        assertEquals(Set.of(1L), result);
    }

    @Test
    void removeFromInbox_isIdempotent() {
        Fixture fixture = new Fixture();

        fixture.repository.removeFromInbox(7L, 101L);
        fixture.repository.removeFromInbox(7L, 101L);

        verify(fixture.zSetOperations, Mockito.times(2)).remove("feed:inbox:7", "101");
    }

    @Test
    void removeFromInbox_nullArgsAreNoOp() {
        Fixture fixture = new Fixture();

        fixture.repository.removeFromInbox(null, 101L);
        fixture.repository.removeFromInbox(7L, null);

        verify(fixture.zSetOperations, never()).remove(anyString(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private static ZSetOperations.TypedTuple<String> tuple(String value, Double score) {
        ZSetOperations.TypedTuple<String> tuple = Mockito.mock(ZSetOperations.TypedTuple.class);
        when(tuple.getValue()).thenReturn(value);
        when(tuple.getScore()).thenReturn(score);
        return tuple;
    }

    private static final class Fixture {
        private final StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ZSetOperations<String, String> zSetOperations = Mockito.mock(ZSetOperations.class);
        private final FeedInboxProperties properties = new FeedInboxProperties();
        private final FeedTimelineRepository repository = new FeedTimelineRepository(redisTemplate, properties);

        private Fixture() {
            properties.setMaxSize(1000);
            properties.setTtlDays(30);
            when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
            when(redisTemplate.getExpire(anyString())).thenReturn(1000L);
            when(zSetOperations.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                    .thenReturn(new HashSet<>());
        }
    }
}
