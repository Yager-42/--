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
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisCallback;
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
        assertEquals(0L, service.getCount(7L, UserCounterType.FAVS_RECEIVED));
    }

    @Test
    void malformedReceivedSnapshotTriggersRebuildFromOwnedPostCounters() {
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
        when(contentRepository.listPublishedPostIdsByUser(9L)).thenReturn(List.of(
                cn.nexus.domain.social.model.entity.ContentPostEntity.builder().postId(101L).build(),
                cn.nexus.domain.social.model.entity.ContentPostEntity.builder().postId(102L).build()));
        when(objectCounterService.getPostCounts(101L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV)))
                .thenReturn(Map.of("like", 7L, "fav", 3L));
        when(objectCounterService.getPostCounts(102L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV)))
                .thenThrow(new RuntimeException("counter unavailable"));

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        long likeReceived = service.getCount(9L, UserCounterType.LIKES_RECEIVED);

        assertEquals(7L, likeReceived);
        verify(redisTemplate, Mockito.atLeast(2)).execute(any(RedisCallback.class));
        verify(redisTemplate).delete("count:rebuild-lock:user:{9}");
        verify(objectCounterService).getPostCounts(101L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));
        verify(objectCounterService).getPostCounts(102L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));
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
    void incrementLikesReceivedWritesOnlyUserSnapshot() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("ucnt:23")), eq("3"), eq("3"), eq("5")))
                .thenReturn(7L);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        long updated = service.incrementLikesReceived(23L, 3L);

        assertEquals(7L, updated);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("ucnt:23")), eq("3"), eq("3"), eq("5"));
        verify(redisTemplate, never()).opsForHash();
        verify(redisTemplate, never()).execute(any(RedisCallback.class));
    }

    @Test
    void incrementFavsReceivedWritesOnlyUserSnapshotSlotFive() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("ucnt:23")), eq("4"), eq("2"), eq("5")))
                .thenReturn(11L);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        long updated = service.incrementFavsReceived(23L, 2L);

        assertEquals(11L, updated);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("ucnt:23")), eq("4"), eq("2"), eq("5"));
        verify(redisTemplate, never()).opsForHash();
        verify(redisTemplate, never()).execute(any(RedisCallback.class));
    }

    @Test
    void rebuildAllCountersUsesMixedSourcesAndOwnedPostCounters() {
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
        when(contentRepository.listPublishedPostIdsByUser(31L)).thenReturn(List.of(
                cn.nexus.domain.social.model.entity.ContentPostEntity.builder().postId(301L).build(),
                cn.nexus.domain.social.model.entity.ContentPostEntity.builder().postId(302L).build()));
        when(objectCounterService.getPostCounts(301L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV)))
                .thenReturn(Map.of("like", 2L, "fav", 4L));
        when(objectCounterService.getPostCounts(302L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV)))
                .thenReturn(Map.of("like", 5L, "fav", 6L));

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        service.rebuildAllCounters(31L);

        verify(redisTemplate).execute(any(RedisCallback.class));
        verify(objectCounterService).getPostCounts(301L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));
        verify(objectCounterService).getPostCounts(302L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));
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
                        .doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{2L, 3L, 4L, 8L, 9L}, 5)))
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
        assertEquals(8L, counters.getLikesReceived());
        assertEquals(9L, counters.getFavsReceived());
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
                .doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{6L, 7L, 8L, 10L, 11L}, 5)))
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
        assertEquals(10L, counters.getLikesReceived());
        assertEquals(11L, counters.getFavsReceived());
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
                .doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{2L, 3L, 3L, 6L, 7L}, 5)))
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
        assertEquals(6L, counters.getLikesReceived());
        assertEquals(7L, counters.getFavsReceived());
        verify(redisTemplate, Mockito.times(3)).execute(any(RedisCallback.class));
    }

    @Test
    void relationCounters_postsSampleMismatch_shouldTriggerRebuild() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{2L, 3L, 99L, 6L, 7L}, 5)))
                .doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{2L, 3L, 4L, 6L, 7L}, 5)))
                .when(redisTemplate).execute(any(RedisCallback.class));
        when(valueOperations.setIfAbsent(eq("ucnt:chk:71"), eq("1"), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE)
                .thenReturn(Boolean.FALSE);
        when(relationRepository.countActiveRelationsBySource(71L, 1)).thenReturn(2);
        when(relationRepository.countFollowerIds(71L)).thenReturn(3);
        when(contentRepository.countPublishedPostsByUser(71L)).thenReturn(4L);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        UserRelationCounterVO before = service.readRelationCountersWithVerification(71L);
        UserRelationCounterVO counters = service.readRelationCountersWithVerification(71L);

        assertEquals(99L, before.getPosts());
        assertEquals(4L, counters.getPosts());
        verify(redisTemplate, Mockito.times(3)).execute(any(RedisCallback.class));
        verify(contentRepository, Mockito.atLeastOnce()).countPublishedPostsByUser(71L);
    }

    @Test
    void relationCounters_receivedSampleMismatch_shouldTriggerRebuild() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{2L, 3L, 4L, 99L, 88L}, 5)))
                .doReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{2L, 3L, 4L, 6L, 7L}, 5)))
                .when(redisTemplate).execute(any(RedisCallback.class));
        when(valueOperations.setIfAbsent(eq("ucnt:chk:81"), eq("1"), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE)
                .thenReturn(Boolean.FALSE);
        when(relationRepository.countActiveRelationsBySource(81L, 1)).thenReturn(2);
        when(relationRepository.countFollowerIds(81L)).thenReturn(3);
        when(contentRepository.countPublishedPostsByUser(81L)).thenReturn(4L);
        when(contentRepository.listPublishedPostIdsByUser(81L)).thenReturn(List.of(
                cn.nexus.domain.social.model.entity.ContentPostEntity.builder().postId(801L).build(),
                cn.nexus.domain.social.model.entity.ContentPostEntity.builder().postId(802L).build()));
        when(objectCounterService.getPostCounts(801L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV)))
                .thenReturn(Map.of("like", 2L, "fav", 3L));
        when(objectCounterService.getPostCounts(802L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV)))
                .thenReturn(Map.of("like", 4L, "fav", 4L));

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        UserRelationCounterVO before = service.readRelationCountersWithVerification(81L);
        UserRelationCounterVO counters = service.readRelationCountersWithVerification(81L);

        assertEquals(99L, before.getLikesReceived());
        assertEquals(88L, before.getFavsReceived());
        assertEquals(6L, counters.getLikesReceived());
        assertEquals(7L, counters.getFavsReceived());
        verify(objectCounterService, Mockito.atLeastOnce()).getPostCounts(801L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));
        verify(objectCounterService, Mockito.atLeastOnce()).getPostCounts(802L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));
    }
}
