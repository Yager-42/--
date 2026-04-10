package cn.nexus.infrastructure.adapter.counter.port;

import cn.nexus.domain.counter.adapter.port.IObjectCounterPort;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.infrastructure.dao.social.ICommentDao;
import cn.nexus.infrastructure.dao.social.po.CommentPO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 对象维度统一计数 Redis 实现。
 *
 * @author codex
 * @since 2026-04-02
 */
@Component
@RequiredArgsConstructor
public class ObjectCounterPort implements IObjectCounterPort {

    private static final String KEY_PREFIX = "counter:object:";
    private static final String REACTION_CNT_KEY_PREFIX = "interact:reaction:cnt:";

    private final StringRedisTemplate redisTemplate;
    private final IReactionCachePort reactionCachePort;
    private final ICommentDao commentDao;

    @Override
    public long getCount(ObjectCounterTarget target) {
        if (target == null) {
            return 0L;
        }
        String raw = redisTemplate.opsForValue().get(key(target));
        Long cached = parseLong(raw);
        if (cached != null && cached >= 0) {
            return cached;
        }
        long rebuilt = rebuild(target);
        redisTemplate.opsForValue().set(key(target), String.valueOf(rebuilt));
        return rebuilt;
    }

    @Override
    public Map<String, Long> batchGetCount(List<ObjectCounterTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(targets.size());
        for (ObjectCounterTarget target : targets) {
            keys.add(target == null ? null : key(target));
        }
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
        } catch (Exception e) {
            values = null;
        }

        Map<String, Long> result = new HashMap<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            ObjectCounterTarget target = targets.get(i);
            if (target == null) {
                continue;
            }
            String raw = values != null && i < values.size() ? values.get(i) : null;
            Long cached = parseLong(raw);
            long count = cached != null && cached >= 0 ? cached : rebuild(target);
            if (cached == null || cached < 0) {
                redisTemplate.opsForValue().set(key(target), String.valueOf(count));
            }
            result.put(target.hashTag(), count);
        }
        return result;
    }

    @Override
    public long increment(ObjectCounterTarget target, long delta) {
        if (target == null) {
            return 0L;
        }
        if (delta == 0) {
            return getCount(target);
        }
        Long updated = redisTemplate.opsForValue().increment(key(target), delta);
        long safe = updated == null ? 0L : Math.max(0L, updated);
        if (updated != null && updated < 0) {
            redisTemplate.opsForValue().set(key(target), "0");
        }
        return safe;
    }

    @Override
    public void setCount(ObjectCounterTarget target, long count) {
        if (target == null) {
            return;
        }
        redisTemplate.opsForValue().set(key(target), String.valueOf(Math.max(0L, count)));
    }

    @Override
    public void evict(ObjectCounterTarget target) {
        if (target == null) {
            return;
        }
        redisTemplate.delete(key(target));
    }

    private long rebuild(ObjectCounterTarget target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null || target.getCounterType() == null) {
            return 0L;
        }
        if (target.getCounterType() == ObjectCounterType.LIKE) {
            return reactionCachePort.getCountFromRedis(ReactionTargetVO.builder()
                    .targetType(target.getTargetType())
                    .targetId(target.getTargetId())
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build());
        }
        if (target.getCounterType() == ObjectCounterType.REPLY
                && target.getTargetType() == ReactionTargetTypeEnumVO.COMMENT) {
            CommentPO comment = commentDao.selectBriefById(target.getTargetId());
            if (comment == null || comment.getReplyCount() == null) {
                return 0L;
            }
            return Math.max(0L, comment.getReplyCount());
        }
        return 0L;
    }

    private String key(ObjectCounterTarget target) {
        ReactionTargetTypeEnumVO targetType = target.getTargetType();
        ObjectCounterType counterType = target.getCounterType();
        Long targetId = target.getTargetId();
        if (counterType == ObjectCounterType.LIKE && targetType != null && targetId != null) {
            return REACTION_CNT_KEY_PREFIX + "{" + targetType.getCode() + ":" + targetId + ":" + ReactionTypeEnumVO.LIKE.getCode() + "}";
        }
        String type = targetType == null ? "" : targetType.getCode();
        String counter = counterType == null ? "" : counterType.getCode();
        String id = targetId == null ? "" : String.valueOf(targetId);
        return KEY_PREFIX + type + ":" + id + ":" + counter;
    }

    private Long parseLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
