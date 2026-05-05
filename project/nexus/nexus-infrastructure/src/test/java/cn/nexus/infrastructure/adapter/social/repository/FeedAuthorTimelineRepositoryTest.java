package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.config.FeedAuthorTimelineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FeedAuthorTimelineRepository}.
 *
 * @since 2026-05-04
 */
class FeedAuthorTimelineRepositoryTest {

    // ── addToTimeline ────────────────────────────────────────────────

    @Test
    void addToTimeline_zaddsWithCorrectKeyAndMember() {
        Fixture fx = new Fixture();
        fx.repository.addToTimeline(42L, 888L, 1700000000000L);

        verify(fx.zSetOps).add("feed:timeline:42", "888", 1700000000000.0);
    }

    @Test
    void addToTimeline_nullArgsAreNoOp() {
        Fixture fx = new Fixture();

        fx.repository.addToTimeline(null, 888L, 1700000000000L);
        fx.repository.addToTimeline(42L, null, 1700000000000L);
        fx.repository.addToTimeline(42L, 888L, null);

        verify(fx.zSetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void addToTimeline_setsTtlWhenNoTtlOnKey() {
        Fixture fx = new Fixture();
        when(fx.redisTemplate.getExpire("feed:timeline:42")).thenReturn(null);

        fx.repository.addToTimeline(42L, 888L, 1700000000000L);

        verify(fx.redisTemplate).expire(eq("feed:timeline:42"), eq(Duration.ofDays(30)));
    }

    @Test
    void addToTimeline_setsTtlWhenNegativeTtl() {
        Fixture fx = new Fixture();
        when(fx.redisTemplate.getExpire("feed:timeline:42")).thenReturn(-1L);

        fx.repository.addToTimeline(42L, 888L, 1700000000000L);

        verify(fx.redisTemplate).expire(eq("feed:timeline:42"), eq(Duration.ofDays(30)));
    }

    @Test
    void addToTimeline_skipsTtlWhenKeyAlreadyHasTtl() {
        Fixture fx = new Fixture();
        when(fx.redisTemplate.getExpire("feed:timeline:42")).thenReturn(1000L);

        fx.repository.addToTimeline(42L, 888L, 1700000000000L);

        verify(fx.redisTemplate, never()).expire(eq("feed:timeline:42"), any(Duration.class));
    }

    @Test
    void addToTimeline_trimsOnlyWhenZCardExceedsMaxSize() {
        Fixture fx = new Fixture();
        fx.properties.setMaxSize(3);
        when(fx.zSetOps.zCard("feed:timeline:42")).thenReturn(4L); // 4 > 3

        fx.repository.addToTimeline(42L, 888L, 1700000000000L);

        // size=4, maxSize=3 -> remove rank 0..(4-3-1) = 0..0
        verify(fx.zSetOps).removeRange("feed:timeline:42", 0L, 0L);
    }

    @Test
    void addToTimeline_doesNotTrimWhenZCardWithinMaxSize() {
        Fixture fx = new Fixture();
        fx.properties.setMaxSize(3);
        when(fx.zSetOps.zCard("feed:timeline:42")).thenReturn(3L); // equal to maxSize

        fx.repository.addToTimeline(42L, 888L, 1700000000000L);

        verify(fx.zSetOps, never()).removeRange(anyString(), anyLong(), anyLong());
    }

    @Test
    void addToTimeline_doesNotTrimWhenZCardNull() {
        Fixture fx = new Fixture();
        when(fx.zSetOps.zCard("feed:timeline:42")).thenReturn(null);

        fx.repository.addToTimeline(42L, 888L, 1700000000000L);

        verify(fx.zSetOps, never()).removeRange(anyString(), anyLong(), anyLong());
    }

    @Test
    void addToTimeline_trimsCorrectRangeWhenSignificantlyOverMaxSize() {
        Fixture fx = new Fixture();
        fx.properties.setMaxSize(10);
        when(fx.zSetOps.zCard("feed:timeline:42")).thenReturn(20L); // 20 - 10 = 10 to remove

        fx.repository.addToTimeline(42L, 888L, 1700000000000L);

        // size=20, maxSize=10 -> remove rank 0..(20-10-1) = 0..9
        verify(fx.zSetOps).removeRange("feed:timeline:42", 0L, 9L);
    }

    // ── removeFromTimeline ───────────────────────────────────────────

    @Test
    void removeFromTimeline_zremsTheMember() {
        Fixture fx = new Fixture();
        fx.repository.removeFromTimeline(42L, 888L);

        verify(fx.zSetOps).remove("feed:timeline:42", "888");
    }

    @Test
    void removeFromTimeline_nullArgsAreNoOp() {
        Fixture fx = new Fixture();

        fx.repository.removeFromTimeline(null, 888L);
        fx.repository.removeFromTimeline(42L, null);

        verify(fx.zSetOps, never()).remove(anyString(), any(Object[].class));
    }

    // ── pageTimeline ─────────────────────────────────────────────────

    @Test
    void pageTimeline_nullAuthorIdReturnsEmpty() {
        Fixture fx = new Fixture();
        List<FeedInboxEntryVO> result = fx.repository.pageTimeline(null, null, null, 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void pageTimeline_returnsEntriesOrderedByPublishTimeDescPostIdDesc() {
        Fixture fx = new Fixture();

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(tuple("301", 3000.0));
        tuples.add(tuple("101", 2000.0));
        tuples.add(tuple("103", 2000.0));
        tuples.add(tuple("102", 2000.0));
        tuples.add(tuple("201", 1000.0));

        when(fx.zSetOps.reverseRangeByScoreWithScores(
                eq("feed:timeline:42"), eq(0.0), eq((double) Long.MAX_VALUE), eq(0L), anyLong()))
                .thenReturn(tuples);

        List<FeedInboxEntryVO> result = fx.repository.pageTimeline(42L, null, null, 10);

        assertEquals(5, result.size());
        assertEquals(301L, result.get(0).getPostId());
        assertEquals(3000L, result.get(0).getPublishTimeMs());
        assertEquals(103L, result.get(1).getPostId());
        assertEquals(2000L, result.get(1).getPublishTimeMs());
        assertEquals(102L, result.get(2).getPostId());
        assertEquals(2000L, result.get(2).getPublishTimeMs());
        assertEquals(101L, result.get(3).getPostId());
        assertEquals(2000L, result.get(3).getPublishTimeMs());
        assertEquals(201L, result.get(4).getPostId());
        assertEquals(1000L, result.get(4).getPublishTimeMs());
    }

    @Test
    void pageTimeline_excludesCursorItemByMaxIdRule() {
        Fixture fx = new Fixture();
        // cursor: timeMs=2000, postId=201
        // Items at or past cursor should be excluded
        // - (2000, 201): equal time, equal postId -> excluded
        // - (2000, 200): equal time, lower postId -> included
        // - (1999, 999): earlier time -> included

        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        tuples.add(tuple("201", 2000.0)); // equals cursor -> excluded
        tuples.add(tuple("200", 2000.0)); // same time, smaller postId -> included
        tuples.add(tuple("999", 1999.0)); // earlier time -> included

        when(fx.zSetOps.reverseRangeByScoreWithScores(
                eq("feed:timeline:42"), eq(0.0), eq(2000.0), eq(0L), anyLong()))
                .thenReturn(tuples);

        // cursor at (2000, 201) -> should exclude exactly this item
        List<FeedInboxEntryVO> result = fx.repository.pageTimeline(42L, 2000L, 201L, 10);

        assertEquals(2, result.size());
        assertEquals(200L, result.get(0).getPostId());
        assertEquals(2000L, result.get(0).getPublishTimeMs());
        assertEquals(999L, result.get(1).getPostId());
        assertEquals(1999L, result.get(1).getPublishTimeMs());
    }

    @Test
    void pageTimeline_respectsLimit() {
        Fixture fx = new Fixture();
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        for (long i = 0; i < 10; i++) {
            tuples.add(tuple(String.valueOf(1000 + i), (double) (10000 - i)));
        }

        when(fx.zSetOps.reverseRangeByScoreWithScores(
                eq("feed:timeline:42"), eq(0.0), eq((double) Long.MAX_VALUE), eq(0L), anyLong()))
                .thenReturn(tuples);

        List<FeedInboxEntryVO> result = fx.repository.pageTimeline(42L, null, null, 3);

        assertEquals(3, result.size());
        assertEquals(1000L, result.get(0).getPostId());
        assertEquals(10000L, result.get(0).getPublishTimeMs());
        assertEquals(1001L, result.get(1).getPostId());
        assertEquals(9999L, result.get(1).getPublishTimeMs());
        assertEquals(1002L, result.get(2).getPostId());
        assertEquals(9998L, result.get(2).getPublishTimeMs());
    }

    @Test
    void pageTimeline_capsLimitAtConfiguredMaxSize() {
        Fixture fx = new Fixture();
        fx.properties.setMaxSize(3);

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        for (long i = 0; i < 5; i++) {
            tuples.add(tuple(String.valueOf(1000 + i), (double) (10000 - i)));
        }

        when(fx.zSetOps.reverseRangeByScoreWithScores(
                eq("feed:timeline:42"), eq(0.0), eq((double) Long.MAX_VALUE), eq(0L), eq(23L)))
                .thenReturn(tuples);

        List<FeedInboxEntryVO> result = fx.repository.pageTimeline(42L, null, null, 10);

        assertEquals(3, result.size());
        assertEquals(1000L, result.get(0).getPostId());
        assertEquals(1001L, result.get(1).getPostId());
        assertEquals(1002L, result.get(2).getPostId());
    }

    @Test
    void pageTimeline_fetchesAdditionalChunksWhenCursorScoreClusterExceedsInitialCushion() {
        Fixture fx = new Fixture();
        Set<ZSetOperations.TypedTuple<String>> firstChunk = new LinkedHashSet<>();
        for (long postId = 1050; postId >= 1029; postId--) {
            firstChunk.add(tuple(String.valueOf(postId), 2000.0));
        }
        Set<ZSetOperations.TypedTuple<String>> secondChunk = new LinkedHashSet<>();
        secondChunk.add(tuple("999", 1999.0));
        secondChunk.add(tuple("998", 1998.0));

        when(fx.zSetOps.reverseRangeByScoreWithScores(
                eq("feed:timeline:42"), eq(0.0), eq(2000.0), eq(0L), eq(22L)))
                .thenReturn(firstChunk);
        when(fx.zSetOps.reverseRangeByScoreWithScores(
                eq("feed:timeline:42"), eq(0.0), eq(2000.0), eq(22L), eq(22L)))
                .thenReturn(secondChunk);

        List<FeedInboxEntryVO> result = fx.repository.pageTimeline(42L, 2000L, 1000L, 2);

        assertEquals(2, result.size());
        assertEquals(999L, result.get(0).getPostId());
        assertEquals(1999L, result.get(0).getPublishTimeMs());
        assertEquals(998L, result.get(1).getPostId());
        assertEquals(1998L, result.get(1).getPublishTimeMs());
    }

    @Test
    void pageTimeline_nullRedisResultReturnsEmpty() {
        Fixture fx = new Fixture();
        when(fx.zSetOps.reverseRangeByScoreWithScores(
                eq("feed:timeline:42"), eq(0.0), eq((double) Long.MAX_VALUE), eq(0L), anyLong()))
                .thenReturn(null);

        List<FeedInboxEntryVO> result = fx.repository.pageTimeline(42L, null, null, 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void pageTimeline_skipsNullTuplesAndBlankMembers() {
        Fixture fx = new Fixture();
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        tuples.add(tuple("301", 3000.0));
        tuples.add(null); // null tuple -> skipped
        tuples.add(tuple("", 2000.0)); // blank member -> skipped
        tuples.add(tuple("   ", 1500.0)); // blank member -> skipped

        when(fx.zSetOps.reverseRangeByScoreWithScores(
                eq("feed:timeline:42"), eq(0.0), eq((double) Long.MAX_VALUE), eq(0L), anyLong()))
                .thenReturn(tuples);

        List<FeedInboxEntryVO> result = fx.repository.pageTimeline(42L, null, null, 10);

        assertEquals(1, result.size());
        assertEquals(301L, result.get(0).getPostId());
    }

    @Test
    void pageTimeline_doesNotExpireKeyOnRead() {
        Fixture fx = new Fixture();
        when(fx.zSetOps.reverseRangeByScoreWithScores(
                eq("feed:timeline:42"), eq(0.0), eq((double) Long.MAX_VALUE), eq(0L), anyLong()))
                .thenReturn(Set.of());

        fx.repository.pageTimeline(42L, null, null, 10);

        verify(fx.redisTemplate, never()).expire(eq("feed:timeline:42"), any(Duration.class));
    }

    // ── timelineExists ───────────────────────────────────────────────

    @Test
    void timelineExists_delegatesToHasKey() {
        Fixture fx = new Fixture();
        when(fx.redisTemplate.hasKey("feed:timeline:42")).thenReturn(true);

        assertTrue(fx.repository.timelineExists(42L));
    }

    @Test
    void timelineExists_returnsFalseWhenKeyAbsent() {
        Fixture fx = new Fixture();
        when(fx.redisTemplate.hasKey("feed:timeline:42")).thenReturn(false);

        assertFalse(fx.repository.timelineExists(42L));
    }

    @Test
    void timelineExists_nullAuthorIdReturnsFalse() {
        Fixture fx = new Fixture();
        assertFalse(fx.repository.timelineExists(null));
        verify(fx.redisTemplate, never()).hasKey(anyString());
    }

    // ── helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ZSetOperations.TypedTuple<String> tuple(String member, Double score) {
        ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
        when(tuple.getValue()).thenReturn(member);
        when(tuple.getScore()).thenReturn(score);
        return tuple;
    }

    private static final class Fixture {
        final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        final ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        final FeedAuthorTimelineProperties properties = new FeedAuthorTimelineProperties();
        final FeedAuthorTimelineRepository repository;

        Fixture() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            properties.setMaxSize(1000);
            properties.setTtlDays(30);
            repository = new FeedAuthorTimelineRepository(redisTemplate, properties);
        }
    }
}
