package cn.nexus.infrastructure.adapter.counter.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import java.util.Arrays;
import java.util.List;
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
        assertEquals(5, post.slotCount());
        assertEquals(20, post.totalPayloadBytes());
        assertNull(comment);
        assertEquals(1, post.slotOf(ObjectCounterType.LIKE));
        assertEquals(2, post.slotOf(ObjectCounterType.FAV));
        assertEquals(List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV), Arrays.asList(ObjectCounterType.values()));
        assertEquals("read", post.fieldNameAt(0));
        assertEquals("like", post.fieldNameAt(1));
        assertEquals("fav", post.fieldNameAt(2));
        assertEquals("comment", post.fieldNameAt(3));
        assertEquals("repost", post.fieldNameAt(4));
    }

    @Test
    void userSchemaExposesLogicalIndexesAndOffsets() {
        assertEquals("v1", CountRedisSchema.user().schemaName());
        assertEquals(List.of(
                UserCounterType.FOLLOWINGS,
                UserCounterType.FOLLOWERS,
                UserCounterType.POSTS,
                UserCounterType.LIKES_RECEIVED,
                UserCounterType.FAVS_RECEIVED), Arrays.asList(UserCounterType.values()));
        assertEquals(1, CountRedisSchema.user().logicalIndexOf(UserCounterType.FOLLOWINGS));
        assertEquals(2, CountRedisSchema.user().logicalIndexOf(UserCounterType.FOLLOWERS));
        assertEquals(3, CountRedisSchema.user().logicalIndexOf(UserCounterType.POSTS));
        assertEquals(4, CountRedisSchema.user().logicalIndexOf(UserCounterType.LIKES_RECEIVED));
        assertEquals(5, CountRedisSchema.user().logicalIndexOf(UserCounterType.FAVS_RECEIVED));
        assertEquals("followings", UserCounterType.FOLLOWINGS.getCode());
        assertEquals("followers", UserCounterType.FOLLOWERS.getCode());
        assertEquals("posts", UserCounterType.POSTS.getCode());
        assertEquals("likesReceived", UserCounterType.LIKES_RECEIVED.getCode());
        assertEquals("favsReceived", UserCounterType.FAVS_RECEIVED.getCode());
        assertEquals(0, CountRedisSchema.user().byteOffsetOf(UserCounterType.FOLLOWINGS));
        assertEquals(4, CountRedisSchema.user().byteOffsetOf(UserCounterType.FOLLOWERS));
        assertEquals(8, CountRedisSchema.user().byteOffsetOf(UserCounterType.POSTS));
        assertEquals(12, CountRedisSchema.user().byteOffsetOf(UserCounterType.LIKES_RECEIVED));
        assertEquals(16, CountRedisSchema.user().byteOffsetOf(UserCounterType.FAVS_RECEIVED));
    }

    @Test
    void keyBuildersRenderZhiguangFamilies() {
        assertEquals("cnt:v1:post:42", CountRedisKeys.objectSnapshot(ReactionTargetTypeEnumVO.POST, 42L));
        assertNull(CountRedisKeys.objectSnapshot(ReactionTargetTypeEnumVO.COMMENT, 99L));
        assertEquals("ucnt:7", CountRedisKeys.userSnapshot(7L));
        assertEquals("agg:v1:post:42",
                CountRedisKeys.objectAggregationBucket(ReactionTargetTypeEnumVO.POST, 42L));
        assertTrue(Arrays.stream(CountRedisKeys.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("objectAggregationBucket") && method.getParameterCount() == 3));
        assertEquals("bm:like:post:42:0",
                CountRedisKeys.bitmapShard(ObjectCounterType.LIKE, ReactionTargetTypeEnumVO.POST, 42L, 0));
        assertEquals("bm:fav:post:42:3",
                CountRedisKeys.bitmapShard(ObjectCounterType.FAV, ReactionTargetTypeEnumVO.POST, 42L, 3));
        assertTrue(Arrays.stream(CountRedisKeys.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("likeBitmapShardIndex")));
        assertEquals("uf:flws:7", CountRedisKeys.relationFollowings(7L));
        assertEquals("uf:fans:7", CountRedisKeys.relationFollowers(7L));
        assertEquals("ucnt:chk:7", CountRedisKeys.userCounterSampleCheck(7L));
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
