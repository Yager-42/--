package cn.nexus.infrastructure.adapter.counter.support;

import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
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
        if (targetType == null || targetId == null) {
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

    public static String objectAggregationBucket(ReactionTargetTypeEnumVO targetType, ObjectCounterType counterType) {
        if (targetType == null || counterType == null) {
            return null;
        }
        return "agg:" + CountRedisSchema.SCHEMA_ID + ":" + lower(targetType) + ":" + counterType.getCode();
    }

    public static String objectAggregationBucket(ReactionTargetTypeEnumVO targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            return null;
        }
        return "agg:" + CountRedisSchema.SCHEMA_ID + ":" + lower(targetType) + ":" + targetId;
    }

    public static String objectAggregationBucket(ReactionTargetTypeEnumVO targetType, Long targetId, long shard) {
        if (targetType == null || targetId == null) {
            return null;
        }
        return objectAggregationBucket(targetType, targetId) + ":" + shard;
    }

    public static String objectAggregationActiveIndex() {
        return "agg:" + CountRedisSchema.SCHEMA_ID + ":active";
    }

    public static String userAggregationBucket(UserCounterType counterType) {
        if (counterType == null) {
            return null;
        }
        return "count:agg:{user}:" + counterType.getCode();
    }

    public static String likeBitmapShard(ReactionTargetTypeEnumVO targetType, Long targetId, long shard) {
        if (targetType == null || targetId == null) {
            return null;
        }
        return "bm:like:" + lower(targetType) + ":" + targetId + ":" + shard;
    }

    public static String likeBitmapShardIndex(ReactionTargetTypeEnumVO targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            return null;
        }
        return "bm:like:" + lower(targetType) + ":" + targetId + ":idx";
    }

    public static String likeFactCount(ReactionTargetTypeEnumVO targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            return null;
        }
        return "count:factcnt:" + lower(targetType) + "_like:{" + targetId + "}";
    }

    public static String objectRebuildLock(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null || target.getCounterType() == null) {
            return null;
        }
        return "count:rebuild-lock:object:{" + target.getTargetType().getCode() + ":" + target.getTargetId()
                + ":" + target.getCounterType().getCode() + "}";
    }

    public static String objectRebuildRateLimit(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null || target.getCounterType() == null) {
            return null;
        }
        return "count:rate-limit:object:{" + target.getTargetType().getCode() + ":" + target.getTargetId()
                + ":" + target.getCounterType().getCode() + "}";
    }

    public static String objectRebuildBackoff(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null || target.getCounterType() == null) {
            return null;
        }
        return "count:rebuild-backoff:object:{" + target.getTargetType().getCode() + ":" + target.getTargetId()
                + ":" + target.getCounterType().getCode() + "}";
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

    public static String userProjectionEventDedup(String eventId, Long userId, UserCounterType counterType) {
        if (eventId == null || eventId.isBlank() || userId == null || counterType == null) {
            return null;
        }
        return "ucnt:evt:dedup:" + userId + ":" + counterType.getCode() + ":" + eventId;
    }

    public static String bitmapField(ObjectCounterType counterType) {
        if (counterType == ObjectCounterType.LIKE) {
            return counterType.getCode();
        }
        return null;
    }

    private static String lower(ReactionTargetTypeEnumVO targetType) {
        return targetType.getCode().toLowerCase();
    }
}
