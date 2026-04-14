package cn.nexus.infrastructure.adapter.counter.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CountRedisSchemaSupportTest {

    @Test
    void objectSchemasExposeExpectedSlots() {
        assertEquals("post_counter", CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST).schemaName());
        assertEquals(0, CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST).slotOf(ObjectCounterType.LIKE));

        assertEquals("comment_counter", CountRedisSchema.forObject(ReactionTargetTypeEnumVO.COMMENT).schemaName());
        assertEquals(Map.of(ObjectCounterType.LIKE, 0, ObjectCounterType.REPLY, 1),
                CountRedisSchema.forObject(ReactionTargetTypeEnumVO.COMMENT).objectSlots());
    }

    @Test
    void userSchemaExposesReservedSlots() {
        assertEquals("user_counter", CountRedisSchema.user().schemaName());
        assertEquals(Map.of(
                UserCounterType.FOLLOWING, 0,
                UserCounterType.FOLLOWER, 1,
                UserCounterType.POST, 2,
                UserCounterType.LIKE_RECEIVED, 3,
                UserCounterType.FAVORITE_RECEIVED, 4
        ), CountRedisSchema.user().userSlots());
    }

    @Test
    void keyBuildersRenderExpectedFamilies() {
        ObjectCounterTarget postLike = ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(42L)
                .counterType(ObjectCounterType.LIKE)
                .build();
        ObjectCounterTarget commentReply = ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.COMMENT)
                .targetId(99L)
                .counterType(ObjectCounterType.REPLY)
                .build();

        assertEquals("count:post:{42}", CountRedisKeys.objectSnapshot(postLike));
        assertEquals("count:comment:{99}", CountRedisKeys.objectSnapshot(commentReply));
        assertEquals("count:user:{7}", CountRedisKeys.userSnapshot(7L));
        assertEquals("count:agg:{post}:like",
                CountRedisKeys.objectAggregationBucket(ReactionTargetTypeEnumVO.POST, ObjectCounterType.LIKE));
        assertEquals("count:agg:{comment}:reply",
                CountRedisKeys.objectAggregationBucket(ReactionTargetTypeEnumVO.COMMENT, ObjectCounterType.REPLY));
        assertEquals("count:agg:{user}:following", CountRedisKeys.userAggregationBucket(UserCounterType.FOLLOWING));
        assertEquals("count:fact:post_like:{42}:0", CountRedisKeys.likeBitmapShard(ReactionTargetTypeEnumVO.POST, 42L, 0));
        assertNull(CountRedisKeys.bitmapField(ObjectCounterType.REPLY));
    }

    @Test
    void codecUsesFixedWidthSlotsAndClampsNegativeValues() {
        byte[] encoded = CountRedisCodec.encodeSlots(new long[]{12L, -5L, 33L}, 3);

        assertEquals(15, encoded.length);
        assertArrayEquals(new long[]{12L, 0L, 33L}, CountRedisCodec.decodeSlots(encoded, 3));
    }
}
