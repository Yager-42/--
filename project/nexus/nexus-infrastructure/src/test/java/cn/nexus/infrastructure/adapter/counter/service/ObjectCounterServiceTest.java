package cn.nexus.infrastructure.adapter.counter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.port.ICounterEventProducer;
import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.PostActionResultVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ObjectCounterServiceTest {

    @Test
    void likeAndDuplicateLikeUseBitmapTruthAndEmitOnlyOnStateTransition() {
        Fixture fixture = new Fixture();
        when(fixture.valueOperations.setBit("bm:like:post:42:0", 7L, true))
                .thenReturn(Boolean.FALSE)
                .thenReturn(Boolean.TRUE);
        when(fixture.valueOperations.getBit("bm:like:post:42:0", 7L)).thenReturn(Boolean.TRUE);
        when(fixture.valueOperations.getBit("bm:fav:post:42:0", 7L)).thenReturn(Boolean.TRUE);
        when(fixture.redisTemplate.execute(any(RedisCallback.class)))
                .thenReturn(snapshotPayload(4L, 2L))
                .thenReturn(snapshotPayload(4L, 2L));

        PostActionResultVO first = fixture.service.likePost(42L, 7L);
        PostActionResultVO duplicate = fixture.service.likePost(42L, 7L);

        assertTrue(first.isChanged());
        assertTrue(first.isLiked());
        assertTrue(first.isFaved());
        assertEquals(4L, first.getLikeCount());
        assertEquals(2L, first.getFavoriteCount());
        assertFalse(duplicate.isChanged());
        assertCounterEvent(fixture, "like", 1, 1L);
        verify(fixture.applicationEventPublisher).publishEvent(any(cn.nexus.domain.counter.model.event.CounterEvent.class));
    }

    @Test
    void unlikeEmitsNegativeDeltaOnlyWhenBitmapWasSet() {
        Fixture fixture = new Fixture();
        when(fixture.valueOperations.setBit("bm:like:post:42:0", 7L, false))
                .thenReturn(Boolean.TRUE)
                .thenReturn(Boolean.FALSE);
        when(fixture.redisTemplate.execute(any(RedisCallback.class))).thenReturn(snapshotPayload(3L, 0L));

        PostActionResultVO first = fixture.service.unlikePost(42L, 7L);
        PostActionResultVO duplicate = fixture.service.unlikePost(42L, 7L);

        assertTrue(first.isChanged());
        assertFalse(duplicate.isChanged());
        assertCounterEvent(fixture, "like", 1, -1L);
    }

    @Test
    void favAndUnfavMirrorLikeUsingFavBitmapAndSlotTwo() {
        Fixture fixture = new Fixture();
        when(fixture.valueOperations.setBit("bm:fav:post:42:0", 7L, true)).thenReturn(Boolean.FALSE);
        when(fixture.valueOperations.setBit("bm:fav:post:42:0", 7L, false)).thenReturn(Boolean.TRUE);
        when(fixture.redisTemplate.execute(any(RedisCallback.class)))
                .thenReturn(snapshotPayload(1L, 5L))
                .thenReturn(snapshotPayload(1L, 4L));

        PostActionResultVO fav = fixture.service.favPost(42L, 7L);
        PostActionResultVO unfav = fixture.service.unfavPost(42L, 7L);

        assertTrue(fav.isChanged());
        assertEquals(5L, fav.getFavoriteCount());
        assertTrue(unfav.isChanged());
        assertCounterEvent(fixture, "fav", 2, 1L);
        assertCounterEvent(fixture, "fav", 2, -1L);
    }

    @Test
    void isPostLikedAndIsPostFavedReadOnlyBitmapState() {
        Fixture fixture = new Fixture();
        when(fixture.valueOperations.getBit("bm:like:post:99:1", 0L)).thenReturn(Boolean.TRUE);
        when(fixture.valueOperations.getBit("bm:fav:post:99:1", 0L)).thenReturn(Boolean.FALSE);

        assertTrue(fixture.service.isPostLiked(99L, 32768L));
        assertFalse(fixture.service.isPostFaved(99L, 32768L));

        verify(fixture.valueOperations, never()).setBit(Mockito.anyString(), anyLong(), Mockito.anyBoolean());
    }

    @Test
    void publicObjectCounterServiceMethodsDoNotAcceptReactionTargetTypeEnum() {
        assertTrue(java.util.Arrays.stream(cn.nexus.domain.counter.adapter.service.IObjectCounterService.class.getMethods())
                .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                .noneMatch(type -> type.getName().endsWith("ReactionTargetTypeEnumVO")));
    }

    @Test
    void nullIdsFailBeforeRedisOrEvents() {
        Fixture fixture = new Fixture();

        assertFalse(fixture.service.likePost(null, 7L).isChanged());
        assertFalse(fixture.service.favPost(42L, null).isChanged());
        assertFalse(fixture.service.isPostLiked(null, 7L));

        verify(fixture.redisTemplate, never()).opsForValue();
        verify(fixture.counterEventProducer, never()).publish(any());
        verify(fixture.applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void getPostCountsRebuildsMalformedSnapshotFromBitmapShardsAndClearsActiveAggFields() {
        Fixture fixture = new Fixture();
        Cursor<byte[]> likeCursor = cursorOf("bm:like:post:42:0", "bm:like:post:42:3");
        Cursor<byte[]> favCursor = cursorOf("bm:fav:post:42:0");
        when(fixture.valueOperations.get("count:rebuild-backoff:object:{post:42}")).thenReturn(null);
        when(fixture.valueOperations.setIfAbsent(eq("count:rate-limit:object:{post:42}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(fixture.valueOperations.setIfAbsent(eq("count:rebuild-lock:object:{post:42}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        RedisConnection connection = Mockito.mock(RedisConnection.class);
        RedisStringCommands stringCommands = Mockito.mock(RedisStringCommands.class);
        when(connection.stringCommands()).thenReturn(stringCommands);
        when(stringCommands.get(any())).thenReturn(new byte[]{1, 2, 3});
        when(connection.scan(any(ScanOptions.class))).thenReturn(likeCursor).thenReturn(favCursor);
        when(stringCommands.bitCount(any())).thenReturn(4L).thenReturn(3L).thenReturn(2L);
        when(stringCommands.set(any(), any())).thenReturn(Boolean.TRUE);
        when(fixture.redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });

        Map<String, Long> values = fixture.service.getPostCounts(42L, List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));

        assertEquals(7L, values.get("like"));
        assertEquals(2L, values.get("fav"));
        verify(fixture.hashOperations).delete("agg:v1:post:42", "1");
        verify(fixture.hashOperations).delete("agg:v1:post:42", "2");
    }

    @Test
    void getPostCountsBatchReturnsZeroForMissingOrMalformedSnapshotsWithoutRebuild() {
        Fixture fixture = new Fixture();
        when(fixture.redisTemplate.execute(any(RedisCallback.class)))
                .thenReturn(snapshotPayload(6L, 2L))
                .thenReturn(new byte[]{1, 2, 3});

        Map<Long, Map<String, Long>> values = fixture.service.getPostCountsBatch(
                List.of(11L, 12L),
                List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV));

        assertEquals(6L, values.get(11L).get("like"));
        assertEquals(2L, values.get(11L).get("fav"));
        assertEquals(0L, values.get(12L).get("like"));
        assertEquals(0L, values.get(12L).get("fav"));
        verify(fixture.valueOperations, never()).setIfAbsent(eq("count:rate-limit:object:{post:12}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    private static byte[] snapshotPayload(long like, long fav) {
        return CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, like, fav, 0L, 0L}, 5));
    }

    private static Cursor<byte[]> cursorOf(String... keys) {
        @SuppressWarnings("unchecked")
        Cursor<byte[]> cursor = Mockito.mock(Cursor.class);
        Boolean[] hasNext = new Boolean[keys.length + 1];
        java.util.Arrays.fill(hasNext, Boolean.TRUE);
        hasNext[keys.length] = Boolean.FALSE;
        byte[][] keyBytes = java.util.Arrays.stream(keys)
                .map(key -> key.getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);
        when(cursor.hasNext()).thenReturn(hasNext[0], java.util.Arrays.copyOfRange(hasNext, 1, hasNext.length));
        when(cursor.next()).thenReturn(keyBytes[0], java.util.Arrays.copyOfRange(keyBytes, 1, keyBytes.length));
        return cursor;
    }

    private static void assertCounterEvent(Fixture fixture, String metric, int slot, long delta) {
        ArgumentCaptor<CounterDeltaEvent> captor = ArgumentCaptor.forClass(CounterDeltaEvent.class);
        verify(fixture.counterEventProducer, Mockito.atLeastOnce()).publish(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(event ->
                "post".equals(event.getTargetType())
                        && Long.valueOf(42L).equals(event.getTargetId())
                        && metric.equals(event.getMetric())
                        && Integer.valueOf(slot).equals(event.getSlot())
                        && Long.valueOf(7L).equals(event.getActorUserId())
                        && Long.valueOf(delta).equals(event.getDelta())
                        && event.getTsMs() != null));
    }

    private static class Fixture {
        private final StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        private final ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        private final org.springframework.data.redis.core.HashOperations<String, Object, Object> hashOperations = Mockito.mock(org.springframework.data.redis.core.HashOperations.class);
        private final ICounterEventProducer counterEventProducer = Mockito.mock(ICounterEventProducer.class);
        private final ApplicationEventPublisher applicationEventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        private final ObjectCounterService service = new ObjectCounterService(redisTemplate, counterEventProducer, applicationEventPublisher);

        private Fixture() {
            assertNotNull(service);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        }
    }
}
