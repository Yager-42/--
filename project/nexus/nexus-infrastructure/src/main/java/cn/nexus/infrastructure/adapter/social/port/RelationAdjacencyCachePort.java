package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.valobj.RelationUserEdgeVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 关系邻接查询门面实现。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
@Component
@RequiredArgsConstructor
public class RelationAdjacencyCachePort implements IRelationAdjacencyCachePort {

    private final IRelationRepository relationRepository;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 执行 addFollow 逻辑。
     *
     * @param sourceId sourceId 参数。类型：{@link Long}
     * @param targetId 目标 ID。类型：{@link Long}
     * @param followTimeMs followTimeMs 参数。类型：{@link Long}
     */
    @Override
    public void addFollow(Long sourceId, Long targetId, Long followTimeMs) {
        // 关系真相源已经落在 DB，这里不再维护邻接缓存。
    }

    /**
     * 执行 removeFollow 逻辑。
     *
     * @param sourceId sourceId 参数。类型：{@link Long}
     * @param targetId 目标 ID。类型：{@link Long}
     */
    @Override
    public void removeFollow(Long sourceId, Long targetId) {
        // 关系真相源已经落在 DB，这里不再维护邻接缓存。
    }

    @Override
    public void addFollowWithTtl(Long sourceId, Long targetId, Long followTimeMs, long ttlSeconds) {
        if (sourceId == null || targetId == null || ttlSeconds <= 0) {
            return;
        }
        String followingsKey = CountRedisKeys.relationFollowings(sourceId);
        String followersKey = CountRedisKeys.relationFollowers(targetId);
        long score = followTimeMs == null ? System.currentTimeMillis() : followTimeMs;
        try {
            stringRedisTemplate.opsForZSet().add(followingsKey, String.valueOf(targetId), score);
            stringRedisTemplate.opsForZSet().add(followersKey, String.valueOf(sourceId), score);
            stringRedisTemplate.expire(followingsKey, ttlSeconds, TimeUnit.SECONDS);
            stringRedisTemplate.expire(followersKey, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 邻接缓存是投影缓存，失败不影响 DB 真相与 ucnt 主链路。
        }
    }

    @Override
    public void removeFollowWithTtl(Long sourceId, Long targetId, long ttlSeconds) {
        if (sourceId == null || targetId == null || ttlSeconds <= 0) {
            return;
        }
        String followingsKey = CountRedisKeys.relationFollowings(sourceId);
        String followersKey = CountRedisKeys.relationFollowers(targetId);
        try {
            stringRedisTemplate.opsForZSet().remove(followingsKey, String.valueOf(targetId));
            stringRedisTemplate.opsForZSet().remove(followersKey, String.valueOf(sourceId));
            stringRedisTemplate.expire(followingsKey, ttlSeconds, TimeUnit.SECONDS);
            stringRedisTemplate.expire(followersKey, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 邻接缓存是投影缓存，失败不影响 DB 真相与 ucnt 主链路。
        }
    }

    /**
     * 执行 listFollowing 逻辑。
     *
     * @param sourceId sourceId 参数。类型：{@link Long}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<Long> listFollowing(Long sourceId, int limit) {
        return pageFollowing(sourceId, null, limit).stream().map(RelationUserEdgeVO::getUserId).toList();
    }

    /**
     * 执行 listFollowers 逻辑。
     *
     * @param targetId 目标 ID。类型：{@link Long}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<Long> listFollowers(Long targetId, int limit) {
        return pageFollowers(targetId, null, limit).stream().map(RelationUserEdgeVO::getUserId).toList();
    }

    /**
     * 执行 pageFollowing 逻辑。
     *
     * @param sourceId sourceId 参数。类型：{@link Long}
     * @param cursor 分页游标。类型：{@link String}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<RelationUserEdgeVO> pageFollowing(Long sourceId, String cursor, int limit) {
        if (sourceId == null || limit <= 0) {
            return List.of();
        }
        return pageFollowingFromDb(sourceId, cursor, limit);
    }

    /**
     * 执行 pageFollowers 逻辑。
     *
     * @param targetId 目标 ID。类型：{@link Long}
     * @param cursor 分页游标。类型：{@link String}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<RelationUserEdgeVO> pageFollowers(Long targetId, String cursor, int limit) {
        if (targetId == null || limit <= 0) {
            return List.of();
        }
        return pageFollowersFromDb(targetId, cursor, limit);
    }

    /**
     * 执行 evict 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     */
    @Override
    public void evict(Long userId) {
        // 当前没有关系邻接缓存需要清理，保留该入口只为减少调用方改动。
    }

    private List<RelationUserEdgeVO> pageFollowingFromDb(Long sourceId, String cursor, int limit) {
        Cursor parsed = Cursor.parse(cursor);
        Date cursorTime = parsed == null ? null : new Date(parsed.score());
        Long cursorUserId = parsed == null ? null : parsed.userId();
        List<RelationEntity> rows = relationRepository.pageActiveFollowsBySource(sourceId, cursorTime, cursorUserId, limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<RelationUserEdgeVO> result = new ArrayList<>(rows.size());
        for (RelationEntity row : rows) {
            if (row == null || row.getTargetId() == null) {
                continue;
            }
            result.add(RelationUserEdgeVO.builder()
                    .userId(row.getTargetId())
                    .followTimeMs(scoreOf(row.getCreateTime()))
                    .build());
        }
        return result;
    }

    private List<RelationUserEdgeVO> pageFollowersFromDb(Long targetId, String cursor, int limit) {
        Cursor parsed = Cursor.parse(cursor);
        Date cursorTime = parsed == null ? null : new Date(parsed.score());
        Long cursorUserId = parsed == null ? null : parsed.userId();
        List<RelationEntity> rows = relationRepository.pageActiveFollowsByTarget(targetId, cursorTime, cursorUserId, limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<RelationUserEdgeVO> result = new ArrayList<>(rows.size());
        for (RelationEntity row : rows) {
            if (row == null || row.getSourceId() == null) {
                continue;
            }
            result.add(RelationUserEdgeVO.builder()
                    .userId(row.getSourceId())
                    .followTimeMs(scoreOf(row.getCreateTime()))
                    .build());
        }
        return result;
    }

    private long scoreOf(Date time) {
        return time == null ? 0L : time.getTime();
    }

    private record Cursor(long score, long userId) {

        private static Cursor parse(String raw) {
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
