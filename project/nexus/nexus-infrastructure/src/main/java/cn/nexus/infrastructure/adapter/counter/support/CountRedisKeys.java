package cn.nexus.infrastructure.adapter.counter.support;

import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;

/**
 * Key builders for Count Redis families.
 */
public final class CountRedisKeys {

    private CountRedisKeys() {
    }

    public static String objectSnapshot(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null) {
            return null;
        }
        return "count:" + lower(target.getTargetType()) + ":{" + target.getTargetId() + "}";
    }

    public static String userSnapshot(Long userId) {
        if (userId == null) {
            return null;
        }
        return "count:user:{" + userId + "}";
    }

    public static String objectAggregationBucket(ReactionTargetTypeEnumVO targetType, ObjectCounterType counterType) {
        if (targetType == null || counterType == null) {
            return null;
        }
        return "count:agg:{" + lower(targetType) + "}:" + counterType.getCode();
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
        return "count:fact:" + lower(targetType) + "_like:{" + targetId + "}:" + shard;
    }

    public static String likeBitmapShardPattern(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null
                || target.getCounterType() != ObjectCounterType.LIKE) {
            return null;
        }
        return "count:fact:" + lower(target.getTargetType()) + "_like:{" + target.getTargetId() + "}:*";
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
