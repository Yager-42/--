package cn.nexus.infrastructure.adapter.counter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class UserCounterServiceTest {

    @Test
    void getCountReadsFromSnapshot() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ucnt:7"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{6L, 8L, 2L, 13L, 0L}, 5)));

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        assertEquals(6L, service.getCount(7L, UserCounterType.FOLLOWING));
        assertEquals(8L, service.getCount(7L, UserCounterType.FOLLOWER));
        assertEquals(2L, service.getCount(7L, UserCounterType.POST));
        assertEquals(13L, service.getCount(7L, UserCounterType.LIKE_RECEIVED));
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
        when(valueOperations.get("ucnt:9")).thenReturn("not-base64");
        when(valueOperations.setIfAbsent(eq("count:rate-limit:user:{9}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:user:{9}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(relationRepository.countActiveRelationsBySource(9L, 1)).thenReturn(4);
        when(relationRepository.countFollowerIds(9L)).thenReturn(5);
        when(contentRepository.countPublishedPostsByUser(9L)).thenReturn(2L);
        when(contentRepository.listPublishedPostIdsByUser(9L)).thenReturn(List.of(
                ContentPostEntity.builder().postId(901L).build(),
                ContentPostEntity.builder().postId(902L).build()));
        when(objectCounterService.getCounts(eq(ReactionTargetTypeEnumVO.POST), eq(901L), eq(List.of(ObjectCounterType.LIKE))))
                .thenReturn(Map.of("like", 3L));
        when(objectCounterService.getCounts(eq(ReactionTargetTypeEnumVO.POST), eq(902L), eq(List.of(ObjectCounterType.LIKE))))
                .thenReturn(Map.of("like", 2L));

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        long likeReceived = service.getCount(9L, UserCounterType.LIKE_RECEIVED);

        assertEquals(5L, likeReceived);
        verify(valueOperations).set(eq("ucnt:9"),
                eq(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{4L, 5L, 2L, 5L, 0L}, 5))));
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
        when(valueOperations.get("ucnt:15")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("count:rate-limit:user:{15}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        long count = service.getCount(15L, UserCounterType.FOLLOWING);

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
        when(valueOperations.get("ucnt:23"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{1L, 2L, 0L, 4L, 0L}, 5)));

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        long updated = service.incrementLikesReceived(23L, 3L);

        assertEquals(7L, updated);
        verify(hashOperations).increment("count:agg:{user}:like_received", "23", 3L);
        verify(valueOperations).set("ucnt:23",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{1L, 2L, 0L, 7L, 0L}, 5)));
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
        when(relationRepository.countActiveRelationsBySource(31L, 1)).thenReturn(12);
        when(relationRepository.countFollowerIds(31L)).thenReturn(21);
        when(contentRepository.countPublishedPostsByUser(31L)).thenReturn(3L);
        when(contentRepository.listPublishedPostIdsByUser(31L)).thenReturn(List.of(
                ContentPostEntity.builder().postId(3101L).build(),
                ContentPostEntity.builder().postId(3102L).build(),
                ContentPostEntity.builder().postId(3103L).build()));
        when(objectCounterService.getCounts(eq(ReactionTargetTypeEnumVO.POST), eq(3101L), eq(List.of(ObjectCounterType.LIKE))))
                .thenReturn(Map.of("like", 1L));
        when(objectCounterService.getCounts(eq(ReactionTargetTypeEnumVO.POST), eq(3102L), eq(List.of(ObjectCounterType.LIKE))))
                .thenReturn(Map.of("like", 4L));
        when(objectCounterService.getCounts(eq(ReactionTargetTypeEnumVO.POST), eq(3103L), eq(List.of(ObjectCounterType.LIKE))))
                .thenReturn(Map.of());

        UserCounterService service = new UserCounterService(redisTemplate, relationRepository, contentRepository, objectCounterService);

        service.rebuildAllCounters(31L);

        verify(valueOperations).set("ucnt:31",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{12L, 21L, 3L, 5L, 0L}, 5)));
    }
}
