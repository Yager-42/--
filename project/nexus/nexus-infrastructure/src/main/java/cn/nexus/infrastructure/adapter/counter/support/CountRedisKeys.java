package cn.nexus.infrastructure.adapter.counter.support;

import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;

/**
 * Key builders for Count Redis families.
 */
public final class CountRedisKeys {

    public static final int CHUNK_SIZE = 32768;

    private CountRedisKeys() {
    }

    public static String objectSnapshot(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null) {
            return null;
        }
        return objectSnapshot(target.getTargetType(), target.getTargetId());
    }

    public static String objectSnapshot(ReactionTargetTypeEnumVO targetType, Long targetId) {
        if (targetType == null || targetId == null || targetType != ReactionTargetTypeEnumVO.POST) {
            return null;
        }
        return "cnt:" + CountRedisSchema.SCHEMA_ID + ":" + lower(targetType) + ":" + targetId;
    }

    public static String userSnapshot(Long userId) {
        if (userId == null) {
            return null;
        }
        return "ucnt:" + userId;
    }

    public static String objectAggregationBucket(ReactionTargetTypeEnumVO targetType, Long targetId) {
        if (targetType == null || targetId == null || targetType != ReactionTargetTypeEnumVO.POST) {
            return null;
        }
        return "agg:" + CountRedisSchema.SCHEMA_ID + ":" + lower(targetType) + ":" + targetId;
    }

    public static String objectAggregationActiveIndex() {
        return "agg:" + CountRedisSchema.SCHEMA_ID + ":active";
    }

    public static String bitmapShard(ObjectCounterType counterType, ReactionTargetTypeEnumVO targetType, Long targetId, long shard) {
        if (counterType == null || targetType == null || targetId == null || targetType != ReactionTargetTypeEnumVO.POST) {
            return null;
        }
        if (counterType != ObjectCounterType.LIKE && counterType != ObjectCounterType.FAV) {
            return null;
        }
        return "bm:" + counterType.getCode() + ":" + lower(targetType) + ":" + targetId + ":" + shard;
    }

    public static String objectRebuildLock(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() != ReactionTargetTypeEnumVO.POST || target.getTargetId() == null) {
            return null;
        }
        return "count:rebuild-lock:object:{post:" + target.getTargetId() + "}";
    }

    public static String objectRebuildRateLimit(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() != ReactionTargetTypeEnumVO.POST || target.getTargetId() == null) {
            return null;
        }
        return "count:rate-limit:object:{post:" + target.getTargetId() + "}";
    }

    public static String objectRebuildBackoff(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() != ReactionTargetTypeEnumVO.POST || target.getTargetId() == null) {
            return null;
        }
        return "count:rebuild-backoff:object:{post:" + target.getTargetId() + "}";
    }

    public static String objectRebuildWatermark(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() != ReactionTargetTypeEnumVO.POST || target.getTargetId() == null) {
            return null;
        }
        return "count:rebuild-watermark:object:{post:" + target.getTargetId() + "}";
    }

    public static String objectRebuildWatermark(ObjectCounterTarget target, int slot) {
        String base = objectRebuildWatermark(target);
        if (base == null || slot < 0) {
            return null;
        }
        return base + ":" + slot;
    }

    public static String objectCounterSampleCheck(ReactionTargetTypeEnumVO targetType, Long targetId) {
        if (targetType == null || targetId == null || targetType != ReactionTargetTypeEnumVO.POST) {
            return null;
        }
        return "cnt:chk:post:" + targetId;
    }

    public static String userRebuildLock(Long userId) {
        if (userId == null) {
            return null;
        }
        return "count:rebuild-lock:user:{" + userId + "}";
    }

    public static String userRebuildRateLimit(Long userId) {
        if (userId == null) {
            return null;
        }
        return "count:rate-limit:user:{" + userId + "}";
    }

    public static String relationFollowings(Long userId) {
        if (userId == null) {
            return null;
        }
        return "uf:flws:" + userId;
    }

    public static String relationFollowers(Long userId) {
        if (userId == null) {
            return null;
        }
        return "uf:fans:" + userId;
    }

    public static String userCounterSampleCheck(Long userId) {
        if (userId == null) {
            return null;
        }
        return "ucnt:chk:" + userId;
    }

    public static String bitmapField(ObjectCounterType counterType) {
        if (counterType == ObjectCounterType.LIKE || counterType == ObjectCounterType.FAV) {
            return counterType.getCode();
        }
        return null;
    }

    private static String lower(ReactionTargetTypeEnumVO targetType) {
        return targetType.getCode().toLowerCase();
    }
}
