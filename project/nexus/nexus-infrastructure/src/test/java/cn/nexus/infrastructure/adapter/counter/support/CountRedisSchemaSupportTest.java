package cn.nexus.infrastructure.adapter.counter.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import org.junit.jupiter.api.Test;

class CountRedisSchemaSupportTest {

    @Test
    void objectSchemaUsesZhiguangFixedSlots() {
        CountRedisSchema post = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST);
        CountRedisSchema comment = CountRedisSchema.forObject(ReactionTargetTypeEnumVO.COMMENT);

        assertEquals("v1", CountRedisSchema.SCHEMA_ID);
        assertEquals(5, CountRedisSchema.SCHEMA_LEN);
        assertEquals(4, CountRedisSchema.FIELD_SIZE);
        assertEquals("v1", post.schemaName());
        assertEquals("v1", comment.schemaName());
        assertEquals(5, post.slotCount());
        assertEquals(5, comment.slotCount());
        assertEquals(20, post.totalPayloadBytes());
        assertEquals(1, post.slotOf(ObjectCounterType.LIKE));
        assertEquals(-1, comment.slotOf(ObjectCounterType.REPLY));
        assertEquals("read", post.fieldNameAt(0));
        assertEquals("like", post.fieldNameAt(1));
        assertEquals("fav", post.fieldNameAt(2));
        assertEquals("comment", post.fieldNameAt(3));
        assertEquals("repost", post.fieldNameAt(4));
    }

    @Test
    void userSchemaExposesLogicalIndexesAndOffsets() {
        assertEquals("v1", CountRedisSchema.user().schemaName());
        assertEquals(1, CountRedisSchema.user().logicalIndexOf(UserCounterType.FOLLOWING));
        assertEquals(2, CountRedisSchema.user().logicalIndexOf(UserCounterType.FOLLOWER));
        assertEquals(3, CountRedisSchema.user().logicalIndexOf(UserCounterType.POST));
        assertEquals(4, CountRedisSchema.user().logicalIndexOf(UserCounterType.LIKE_RECEIVED));
        assertEquals(5, CountRedisSchema.user().logicalIndexOf(UserCounterType.FAVORITE_RECEIVED));
        assertEquals(0, CountRedisSchema.user().byteOffsetOf(UserCounterType.FOLLOWING));
        assertEquals(4, CountRedisSchema.user().byteOffsetOf(UserCounterType.FOLLOWER));
        assertEquals(8, CountRedisSchema.user().byteOffsetOf(UserCounterType.POST));
        assertEquals(12, CountRedisSchema.user().byteOffsetOf(UserCounterType.LIKE_RECEIVED));
        assertEquals(16, CountRedisSchema.user().byteOffsetOf(UserCounterType.FAVORITE_RECEIVED));
    }

    @Test
    void keyBuildersRenderZhiguangFamilies() {
        ObjectCounterTarget postLike = ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(42L)
                .counterType(ObjectCounterType.LIKE)
                .build();

        assertEquals("cnt:v1:post:42", CountRedisKeys.objectSnapshot(postLike));
        assertEquals("cnt:v1:comment:99", CountRedisKeys.objectSnapshot(ReactionTargetTypeEnumVO.COMMENT, 99L));
        assertEquals("ucnt:7", CountRedisKeys.userSnapshot(7L));
        assertEquals("agg:v1:post:42",
                CountRedisKeys.objectAggregationBucket(ReactionTargetTypeEnumVO.POST, 42L));
        assertEquals("bm:like:post:42:0", CountRedisKeys.likeBitmapShard(ReactionTargetTypeEnumVO.POST, 42L, 0));
        assertEquals("bm:like:post:42:*", CountRedisKeys.likeBitmapShardPattern(postLike));
        assertEquals("uf:flws:7", CountRedisKeys.relationFollowings(7L));
        assertEquals("uf:fans:7", CountRedisKeys.relationFollowers(7L));
        assertNull(CountRedisKeys.bitmapField(ObjectCounterType.REPLY));
    }

    @Test
    void codecUsesFixedWidthInt32BigEndianAndClamps() {
        byte[] encoded = CountRedisCodec.encodeSlots(new long[]{0x01020304L, -5L, 0x1_0000_0000L}, 3);

        assertEquals(12, encoded.length);
        assertArrayEquals(new long[]{1, 2, 3, 4}, new long[]{
                encoded[0] & 0xFFL,
                encoded[1] & 0xFFL,
                encoded[2] & 0xFFL,
                encoded[3] & 0xFFL
        });
        assertArrayEquals(new long[]{0x01020304L, 0L, 0xFFFF_FFFFL}, CountRedisCodec.decodeSlots(encoded, 3));
    }
}
