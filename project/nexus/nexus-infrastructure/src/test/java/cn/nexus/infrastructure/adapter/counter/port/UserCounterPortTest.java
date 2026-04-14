package cn.nexus.infrastructure.adapter.counter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class UserCounterPortTest {

    @Test
    void getCountReadsFollowingFollowerAndLikeReceivedFromUserSnapshot() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:user:{7}"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{6L, 8L, 0L, 13L, 0L}, 5)));

        UserCounterPort port = new UserCounterPort(redisTemplate, relationRepository);

        assertEquals(6L, port.getCount(7L, UserCounterType.FOLLOWING));
        assertEquals(8L, port.getCount(7L, UserCounterType.FOLLOWER));
        assertEquals(13L, port.getCount(7L, UserCounterType.LIKE_RECEIVED));
    }

    @Test
    void malformedLikeReceivedSnapshotReturnsZeroWithoutRelationRebuildFallback() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:user:{9}")).thenReturn("not-base64");

        UserCounterPort port = new UserCounterPort(redisTemplate, relationRepository);

        assertEquals(0L, port.getCount(9L, UserCounterType.LIKE_RECEIVED));
        verify(valueOperations, never()).set(any(), any());
    }

    @Test
    void missingFollowingSnapshotRebuildsFromRelationTruthAndWritesReservedSlots() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:user:{11}")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("count:rate-limit:user:{11}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:user:{11}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(relationRepository.countActiveRelationsBySource(11L, 1)).thenReturn(7);
        when(relationRepository.countFollowerIds(11L)).thenReturn(4);

        UserCounterPort port = new UserCounterPort(redisTemplate, relationRepository);

        long count = port.getCount(11L, UserCounterType.FOLLOWING);

        assertEquals(7L, count);
        verify(valueOperations).set("count:user:{11}",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{7L, 4L, 0L, 0L, 0L}, 5)));
        verify(redisTemplate).delete("count:rebuild-lock:user:{11}");
    }

    @Test
    void malformedFollowerSnapshotRebuildsFromRelationTruthAndReturnsFollowerCount() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:user:{12}")).thenReturn("not-base64");
        when(valueOperations.setIfAbsent(eq("count:rate-limit:user:{12}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:user:{12}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(relationRepository.countActiveRelationsBySource(12L, 1)).thenReturn(3);
        when(relationRepository.countFollowerIds(12L)).thenReturn(9);

        UserCounterPort port = new UserCounterPort(redisTemplate, relationRepository);

        long count = port.getCount(12L, UserCounterType.FOLLOWER);

        assertEquals(9L, count);
        verify(valueOperations).set("count:user:{12}",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{3L, 9L, 0L, 0L, 0L}, 5)));
        verify(redisTemplate).delete("count:rebuild-lock:user:{12}");
    }

    @Test
    void missingFollowingSnapshotReturnsZeroWhenRateLimited() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:user:{15}")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("count:rate-limit:user:{15}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        UserCounterPort port = new UserCounterPort(redisTemplate, relationRepository);

        long count = port.getCount(15L, UserCounterType.FOLLOWING);

        assertEquals(0L, count);
        verify(relationRepository, never()).countActiveRelationsBySource(any(), any());
        verify(relationRepository, never()).countFollowerIds(any());
        verify(valueOperations, never()).set(eq("count:user:{15}"), any());
    }

    @Test
    void malformedFollowerSnapshotReturnsZeroWhenRebuildLockIsBusy() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:user:{16}")).thenReturn("not-base64");
        when(valueOperations.setIfAbsent(eq("count:rate-limit:user:{16}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:user:{16}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        UserCounterPort port = new UserCounterPort(redisTemplate, relationRepository);

        long count = port.getCount(16L, UserCounterType.FOLLOWER);

        assertEquals(0L, count);
        verify(relationRepository, never()).countActiveRelationsBySource(any(), any());
        verify(relationRepository, never()).countFollowerIds(any());
        verify(valueOperations, never()).set(eq("count:user:{16}"), any());
    }

    @Test
    void malformedFollowingSnapshotRebuildsWhenGuardsAllow() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:user:{17}")).thenReturn("not-base64");
        when(valueOperations.setIfAbsent(eq("count:rate-limit:user:{17}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:user:{17}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(relationRepository.countActiveRelationsBySource(17L, 1)).thenReturn(10);
        when(relationRepository.countFollowerIds(17L)).thenReturn(6);

        UserCounterPort port = new UserCounterPort(redisTemplate, relationRepository);

        long count = port.getCount(17L, UserCounterType.FOLLOWING);

        assertEquals(10L, count);
        verify(valueOperations).set("count:user:{17}",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{10L, 6L, 0L, 0L, 0L}, 5)));
        verify(redisTemplate).delete("count:rebuild-lock:user:{17}");
    }

    @Test
    void setIncrementAndEvictOperateOnUserSnapshotKey() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("count:user:{5}"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{2L, 4L, 0L, 1L, 0L}, 5)))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 4L, 0L, 1L, 0L}, 5)));

        UserCounterPort port = new UserCounterPort(redisTemplate, relationRepository);

        port.setCount(5L, UserCounterType.LIKE_RECEIVED, 6L);
        long incremented = port.increment(5L, UserCounterType.FOLLOWING, -9L);
        port.evict(5L, UserCounterType.FOLLOWING);

        assertEquals(0L, incremented);
        verify(hashOperations).increment("count:agg:{user}:following", "5", -9L);
        verify(valueOperations).set("count:user:{5}",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{2L, 4L, 0L, 6L, 0L}, 5)));
        verify(valueOperations).set("count:user:{5}",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 4L, 0L, 1L, 0L}, 5)));
        verify(redisTemplate).delete("count:user:{5}");
    }

    @Test
    void incrementLikeReceivedWritesUserAggregationBucketAndSnapshot() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("count:user:{23}"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{1L, 2L, 0L, 4L, 0L}, 5)));

        UserCounterPort port = new UserCounterPort(redisTemplate, relationRepository);

        long updated = port.increment(23L, UserCounterType.LIKE_RECEIVED, 3L);

        assertEquals(7L, updated);
        verify(hashOperations).increment("count:agg:{user}:like_received", "23", 3L);
        verify(valueOperations).set("count:user:{23}",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{1L, 2L, 0L, 7L, 0L}, 5)));
    }
}
