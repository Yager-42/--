package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.valobj.RelationUserEdgeVO;
import cn.nexus.infrastructure.dao.social.IFollowerDao;
import cn.nexus.infrastructure.dao.social.po.FollowerPO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

/**
 * Redis 邻接缓存（ZSet，有序分页）。
 */
@Component
@RequiredArgsConstructor
public class RelationAdjacencyCachePort implements IRelationAdjacencyCachePort {

    private static final String KEY_FOLLOWING = "social:adj:following:z:";
    private static final String KEY_FOLLOWERS = "social:adj:followers:z:";
    private static final int RELATION_FOLLOW = 1;
    private static final int STATUS_ACTIVE = 1;
    private static final int REBUILD_PAGE_SIZE = 1000;
    private static final int PAGE_FETCH_FACTOR = 4;

    private final StringRedisTemplate redisTemplate;
    private final IRelationRepository relationRepository;
    private final IFollowerDao followerDao;

    @Override
    public void addFollow(Long sourceId, Long targetId, Long followTimeMs) {
        if (sourceId == null || targetId == null) {
            return;
        }
        long score = followTimeMs == null ? System.currentTimeMillis() : followTimeMs;
        redisTemplate.opsForZSet().add(followingKey(sourceId), targetId.toString(), score);
        redisTemplate.opsForZSet().add(followersKey(targetId), sourceId.toString(), score);
    }

    @Override
    public void removeFollow(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) {
            return;
        }
        redisTemplate.opsForZSet().remove(followingKey(sourceId), targetId.toString());
        redisTemplate.opsForZSet().remove(followersKey(targetId), sourceId.toString());
    }

    @Override
    public List<Long> listFollowing(Long sourceId, int limit) {
        return pageFollowing(sourceId, null, limit).stream().map(RelationUserEdgeVO::getUserId).toList();
    }

    @Override
    public List<Long> listFollowers(Long targetId, int limit) {
        return pageFollowers(targetId, null, limit).stream().map(RelationUserEdgeVO::getUserId).toList();
    }

    @Override
    public List<RelationUserEdgeVO> pageFollowing(Long sourceId, String cursor, int limit) {
        if (sourceId == null || limit <= 0) {
            return List.of();
        }
        ensureFollowingCache(sourceId);
        return pageZset(followingKey(sourceId), cursor, limit, true);
    }

    @Override
    public List<RelationUserEdgeVO> pageFollowers(Long targetId, String cursor, int limit) {
        if (targetId == null || limit <= 0) {
            return List.of();
        }
        ensureFollowerCache(targetId);
        return pageZset(followersKey(targetId), cursor, limit, false);
    }

    @Override
    public void rebuildFollowing(Long sourceId) {
        if (sourceId == null) {
            return;
        }
        redisTemplate.delete(followingKey(sourceId));
        int offset = 0;
        while (true) {
            List<FollowerPO> rows = followerDao.selectFollowingRows(sourceId, offset, REBUILD_PAGE_SIZE);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (FollowerPO row : rows) {
                if (row == null || row.getUserId() == null) {
                    continue;
                }
                long score = row.getCreateTime() == null ? System.currentTimeMillis() : row.getCreateTime().getTime();
                redisTemplate.opsForZSet().add(followingKey(sourceId), row.getUserId().toString(), score);
            }
            if (rows.size() < REBUILD_PAGE_SIZE) {
                break;
            }
            offset += rows.size();
        }
    }

    @Override
    public void rebuildFollowers(Long targetId) {
        if (targetId == null) {
            return;
        }
        redisTemplate.delete(followersKey(targetId));
        int offset = 0;
        while (true) {
            List<FollowerPO> rows = followerDao.selectFollowerRows(targetId, offset, REBUILD_PAGE_SIZE);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (FollowerPO row : rows) {
                if (row == null || row.getFollowerId() == null) {
                    continue;
                }
                long score = row.getCreateTime() == null ? System.currentTimeMillis() : row.getCreateTime().getTime();
                redisTemplate.opsForZSet().add(followersKey(targetId), row.getFollowerId().toString(), score);
            }
            if (rows.size() < REBUILD_PAGE_SIZE) {
                break;
            }
            offset += rows.size();
        }
    }

    @Override
    public void evict(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(followingKey(userId));
        redisTemplate.delete(followersKey(userId));
    }

    private void ensureFollowingCache(Long sourceId) {
        Boolean exists = redisTemplate.hasKey(followingKey(sourceId));
        Long size = redisTemplate.opsForZSet().zCard(followingKey(sourceId));
        int dbCount = relationRepository.countActiveRelationsBySource(sourceId, RELATION_FOLLOW);
        long zsetSize = size == null ? 0L : size;
        if (!Boolean.TRUE.equals(exists) || zsetSize < dbCount) {
            rebuildFollowing(sourceId);
        }
    }

    private void ensureFollowerCache(Long targetId) {
        Boolean exists = redisTemplate.hasKey(followersKey(targetId));
        Long size = redisTemplate.opsForZSet().zCard(followersKey(targetId));
        int dbCount = relationRepository.countFollowerIds(targetId);
        long zsetSize = size == null ? 0L : size;
        if (!Boolean.TRUE.equals(exists) || zsetSize < dbCount) {
            rebuildFollowers(targetId);
        }
    }

    private List<RelationUserEdgeVO> pageZset(String key, String cursor, int limit, boolean following) {
        Cursor parsed = Cursor.parse(cursor);
        double maxScore = parsed == null ? Double.MAX_VALUE : parsed.score();
        int fetchCount = Math.max(limit * PAGE_FETCH_FACTOR, Math.max(50, limit));
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0D, maxScore, 0, fetchCount);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }

        List<RelationUserEdgeVO> all = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple == null || tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            Long userId = parseLong(tuple.getValue());
            if (userId == null) {
                continue;
            }
            all.add(RelationUserEdgeVO.builder()
                    .userId(userId)
                    .followTimeMs(tuple.getScore().longValue())
                    .build());
        }
        if (all.isEmpty()) {
            return List.of();
        }

        all.sort(Comparator.comparing(RelationUserEdgeVO::getFollowTimeMs, Comparator.nullsLast(Long::compareTo)).reversed()
                .thenComparing(RelationUserEdgeVO::getUserId, Comparator.nullsLast(Long::compareTo)).reversed());

        List<RelationUserEdgeVO> result = new ArrayList<>(limit);
        Set<Long> seen = new HashSet<>();
        for (RelationUserEdgeVO edge : all) {
            if (edge == null || edge.getUserId() == null || !seen.add(edge.getUserId())) {
                continue;
            }
            if (parsed != null && sameScore(edge.getFollowTimeMs(), parsed.score()) && edge.getUserId() >= parsed.userId()) {
                continue;
            }
            result.add(edge);
            if (result.size() >= limit) {
                break;
            }
        }
        return result.isEmpty() ? Collections.emptyList() : result;
    }

    private boolean sameScore(Long a, long b) {
        return a != null && a == b;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String followingKey(Long sourceId) {
        return KEY_FOLLOWING + sourceId;
    }

    private String followersKey(Long targetId) {
        return KEY_FOLLOWERS + targetId;
    }

    private record Cursor(long score, long userId) {
        static Cursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] parts = raw.trim().split(":", 2);
            if (parts.length != 2) {
                return null;
            }
            try {
                return new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
