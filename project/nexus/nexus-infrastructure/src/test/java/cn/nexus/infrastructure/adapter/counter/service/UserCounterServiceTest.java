package cn.nexus.infrastructure.adapter.counter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.counter.model.valobj.UserRelationCounterVO;
import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class UserCounterServiceTest {

    @Test
    void getCountReadsFromSnapshot() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        Mockito.doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{6L, 8L, 2L, 13L, 0L}, 5)))
                .when(redisTemplate).execute(any(RedisCallback.class));

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        assertEquals(6L, service.getCount(7L, UserCounterType.FOLLOWINGS));
        assertEquals(8L, service.getCount(7L, UserCounterType.FOLLOWERS));
        assertEquals(2L, service.getCount(7L, UserCounterType.POSTS));
        assertEquals(13L, service.getCount(7L, UserCounterType.LIKES_RECEIVED));
    }

    @Test
    void malformedLikeReceivedSnapshotTriggersMixedRebuild() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.doReturn(new byte[]{1, 2, 3})
                .doReturn(Boolean.TRUE)
                .when(redisTemplate).execute(any(RedisCallback.class));
        when(valueOperations.setIfAbsent(eq("count:rate-limit:user:{9}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:user:{9}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(relationRepository.countActiveRelationsBySource(9L, 1)).thenReturn(4);
        when(relationRepository.countFollowerIds(9L)).thenReturn(5);
        when(contentRepository.countPublishedPostsByUser(9L)).thenReturn(2L);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        long likeReceived = service.getCount(9L, UserCounterType.LIKES_RECEIVED);

        assertEquals(0L, likeReceived);
        verify(redisTemplate, Mockito.times(2)).execute(any(RedisCallback.class));
        verify(redisTemplate).delete("count:rebuild-lock:user:{9}");
    }

    @Test
    void missingFollowingSnapshotReturnsZeroWhenRateLimited() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.doReturn(null).when(redisTemplate).execute(any(RedisCallback.class));
        when(valueOperations.setIfAbsent(eq("count:rate-limit:user:{15}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        long count = service.getCount(15L, UserCounterType.FOLLOWINGS);

        assertEquals(0L, count);
        verify(relationRepository, never()).countActiveRelationsBySource(any(), any());
        verify(contentRepository, never()).countPublishedPostsByUser(anyLong());
        verify(valueOperations, never()).set(eq("ucnt:15"), any());
    }

    @Test
    void incrementLikesReceivedWritesAggregationBucketAndSnapshot() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("ucnt:23")), eq("3"), eq("3"), eq("5")))
                .thenReturn(7L);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        long updated = service.incrementLikesReceived(23L, 3L);

        assertEquals(7L, updated);
        verify(hashOperations).increment("count:agg:{user}:like_received", "23", 3L);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("ucnt:23")), eq("3"), eq("3"), eq("5"));
        verify(redisTemplate, never()).execute(any(RedisCallback.class));
    }

    @Test
    void rebuildAllCountersUsesMixedSourcesAndWritesFavoriteZero() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.doReturn(Boolean.TRUE).when(redisTemplate).execute(any(RedisCallback.class));
        when(relationRepository.countActiveRelationsBySource(31L, 1)).thenReturn(12);
        when(relationRepository.countFollowerIds(31L)).thenReturn(21);
        when(contentRepository.countPublishedPostsByUser(31L)).thenReturn(3L);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        service.rebuildAllCounters(31L);

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void relationCounters_missingSnapshot_shouldRebuildAndReturnPublicFields() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.doReturn(null)
                .doReturn(Boolean.TRUE)
                .doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{2L, 3L, 4L, 0L, 0L}, 5)))
                .when(redisTemplate).execute(any(RedisCallback.class));
        when(relationRepository.countActiveRelationsBySource(41L, 1)).thenReturn(2);
        when(relationRepository.countFollowerIds(41L)).thenReturn(3);
        when(contentRepository.countPublishedPostsByUser(41L)).thenReturn(4L);
        when(valueOperations.setIfAbsent(eq("ucnt:chk:41"), eq("1"), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        UserRelationCounterVO counters = service.readRelationCountersWithVerification(41L);

        assertEquals(2L, counters.getFollowings());
        assertEquals(3L, counters.getFollowers());
        assertEquals(4L, counters.getPosts());
        assertEquals(0L, counters.getLikedPosts());
        verify(redisTemplate, Mockito.times(3)).execute(any(RedisCallback.class));
    }

    @Test
    void relationCounters_oversizedSnapshot_shouldRebuildAsMalformed() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        byte[] oversized = new byte[24];
        Mockito.doReturn(oversized)
                .doReturn(Boolean.TRUE)
                .doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{6L, 7L, 8L, 0L, 0L}, 5)))
                .when(redisTemplate).execute(any(RedisCallback.class));
        when(relationRepository.countActiveRelationsBySource(61L, 1)).thenReturn(6);
        when(relationRepository.countFollowerIds(61L)).thenReturn(7);
        when(contentRepository.countPublishedPostsByUser(61L)).thenReturn(8L);
        when(valueOperations.setIfAbsent(eq("ucnt:chk:61"), eq("1"), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        UserRelationCounterVO counters = service.readRelationCountersWithVerification(61L);

        assertEquals(6L, counters.getFollowings());
        assertEquals(7L, counters.getFollowers());
        assertEquals(8L, counters.getPosts());
        assertEquals(0L, counters.getLikedPosts());
    }

    @Test
    void relationCounters_sampleMismatch_shouldTriggerRebuild() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{9L, 9L, 3L, 7L, 0L}, 5)))
                .doReturn(Boolean.TRUE)
                .doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{2L, 3L, 3L, 0L, 0L}, 5)))
                .when(redisTemplate).execute(any(RedisCallback.class));
        when(valueOperations.setIfAbsent(eq("ucnt:chk:51"), eq("1"), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE)
                .thenReturn(Boolean.FALSE);
        when(relationRepository.countActiveRelationsBySource(51L, 1)).thenReturn(2);
        when(relationRepository.countFollowerIds(51L)).thenReturn(3);
        when(contentRepository.countPublishedPostsByUser(51L)).thenReturn(3L);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        UserRelationCounterVO before = service.readRelationCountersWithVerification(51L);
        UserRelationCounterVO counters = service.readRelationCountersWithVerification(51L);

        assertEquals(9L, before.getFollowings());
        assertEquals(9L, before.getFollowers());
        assertEquals(2L, counters.getFollowings());
        assertEquals(3L, counters.getFollowers());
        assertEquals(3L, counters.getPosts());
        assertEquals(0L, counters.getLikedPosts());
        verify(redisTemplate, Mockito.times(3)).execute(any(RedisCallback.class));
    }
}
