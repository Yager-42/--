package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Redis 邻接缓存（关注/粉丝集合）。
 */
@Component
@RequiredArgsConstructor
public class RelationAdjacencyCachePort implements IRelationAdjacencyCachePort {

    private final RedisTemplate<String, String> redisTemplate;
    private final IRelationRepository relationRepository;

    private static final String KEY_FOLLOWING = "social:adj:following:"; // source -> targets
    private static final String KEY_FOLLOWERS = "social:adj:followers:"; // target -> sources（普通用户）
    private static final String KEY_FOLLOWER_BUCKET = "social:adj:followers:bucket:"; // target -> sources（热门分桶）
    private static final int RELATION_FOLLOW = 1;
    private static final int HOT_THRESHOLD = 5000;
    private static final int HOT_BUCKETS = 4;

    @Override
    public void addFollow(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) return;
        SetOperations<String, String> ops = redisTemplate.opsForSet();
        ops.add(KEY_FOLLOWING + sourceId, targetId.toString());
        if (useBucket(targetId)) {
            ops.add(followerBucketKey(targetId, sourceId), sourceId.toString());
        } else {
            ops.add(KEY_FOLLOWERS + targetId, sourceId.toString());
        }
    }

    @Override
    public void removeFollow(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) return;
        SetOperations<String, String> ops = redisTemplate.opsForSet();
        ops.remove(KEY_FOLLOWING + sourceId, targetId.toString());
        ops.remove(KEY_FOLLOWERS + targetId, sourceId.toString());
        // 同时尝试从分桶中删除，避免热门用户遗留
        for (int i = 0; i < HOT_BUCKETS; i++) {
            ops.remove(followerBucketKey(targetId, i), sourceId.toString());
        }
    }

    @Override
    public List<Long> listFollowing(Long sourceId, int limit) {
        if (sourceId == null) return List.of();
        Set<String> members = redisTemplate.opsForSet().members(KEY_FOLLOWING + sourceId);
        int dbCount = relationRepository.countRelationsBySource(sourceId, RELATION_FOLLOW);
        if (members == null || members.size() < dbCount) {
            rebuildFollowing(sourceId);
            members = redisTemplate.opsForSet().members(KEY_FOLLOWING + sourceId);
        }
        return toLimitedList(members, limit);
    }

    @Override
    public List<Long> listFollowers(Long targetId, int limit) {
        if (targetId == null) return List.of();
        Set<String> aggregated = new HashSet<>();
        Set<String> base = redisTemplate.opsForSet().members(KEY_FOLLOWERS + targetId);
        if (base != null) {
            aggregated.addAll(base);
        }
        for (int i = 0; i < HOT_BUCKETS; i++) {
            Set<String> bucketMembers = redisTemplate.opsForSet().members(followerBucketKey(targetId, i));
            if (bucketMembers != null) {
                aggregated.addAll(bucketMembers);
            }
        }
        int dbCount = relationRepository.countRelationsByTarget(targetId, RELATION_FOLLOW);
        if (aggregated.size() < dbCount) {
            rebuildFollowers(targetId);
            aggregated.clear();
            Set<String> base2 = redisTemplate.opsForSet().members(KEY_FOLLOWERS + targetId);
            if (base2 != null) {
                aggregated.addAll(base2);
            }
            for (int i = 0; i < HOT_BUCKETS; i++) {
                Set<String> bucketMembers = redisTemplate.opsForSet().members(followerBucketKey(targetId, i));
                if (bucketMembers != null) {
                    aggregated.addAll(bucketMembers);
                }
            }
        }
        return toLimitedList(aggregated, limit);
    }

    @Override
    public void rebuildFollowing(Long sourceId) {
        if (sourceId == null) {
            return;
        }
        evictFollowing(sourceId);
        List<RelationEntity> relations = relationRepository.listRelationsBySource(sourceId, RELATION_FOLLOW);
        if (relations == null || relations.isEmpty()) {
            return;
        }
        relations.forEach(rel -> addFollow(rel.getSourceId(), rel.getTargetId()));
    }

    @Override
    public void rebuildFollowers(Long targetId) {
        if (targetId == null) {
            return;
        }
        evictFollowers(targetId);
        List<RelationEntity> relations = relationRepository.listRelationsByTarget(targetId, RELATION_FOLLOW);
        if (relations == null || relations.isEmpty()) {
            return;
        }
        SetOperations<String, String> ops = redisTemplate.opsForSet();
        for (RelationEntity rel : relations) {
            Long followerId = rel.getSourceId();
            if (followerId == null) {
                continue;
            }
            if (useBucket(targetId)) {
                ops.add(followerBucketKey(targetId, followerId), followerId.toString());
            } else {
                ops.add(KEY_FOLLOWERS + targetId, followerId.toString());
            }
        }
    }

    @Override
    public void evict(Long userId) {
        evictFollowing(userId);
        evictFollowers(userId);
    }

    private List<Long> toLimitedList(Set<String> set, int limit) {
        if (set == null || set.isEmpty()) return List.of();
        List<Long> result = new ArrayList<>();
        int count = 0;
        for (String s : set) {
            if (count >= limit) break;
            try {
                result.add(Long.parseLong(s));
                count++;
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private boolean useBucket(Long targetId) {
        try {
            return relationRepository.countRelationsByTarget(targetId, RELATION_FOLLOW) >= HOT_THRESHOLD;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String followerBucketKey(Long targetId, Long followerId) {
        int bucket = Math.floorMod(followerId != null ? followerId : 0L, HOT_BUCKETS);
        return followerBucketKey(targetId, bucket);
    }

    private String followerBucketKey(Long targetId, int bucketIndex) {
        return KEY_FOLLOWER_BUCKET + targetId + ":b" + bucketIndex;
    }

    private void evictFollowing(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(KEY_FOLLOWING + userId);
    }

    private void evictFollowers(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(KEY_FOLLOWERS + userId);
        for (int i = 0; i < HOT_BUCKETS; i++) {
            redisTemplate.delete(followerBucketKey(userId, i));
        }
    }
}
