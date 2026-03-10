package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.valobj.RelationUserEdgeVO;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 关系查询门面端口实现。
 *
 * <p>本实现不再维护 Redis 邻接缓存，也不再承担 rebuild 协议职责，
 * 关系数据统一以 DB 为真相源。</p>
 */
@Component
@RequiredArgsConstructor
public class RelationAdjacencyCachePort implements IRelationAdjacencyCachePort {

    private final IRelationRepository relationRepository;

    @Override
    public void addFollow(Long sourceId, Long targetId, Long followTimeMs) {
        // 关系真相源已经落在 DB，这里不再维护邻接缓存。
    }

    @Override
    public void removeFollow(Long sourceId, Long targetId) {
        // 关系真相源已经落在 DB，这里不再维护邻接缓存。
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
        return pageFollowingFromDb(sourceId, cursor, limit);
    }

    @Override
    public List<RelationUserEdgeVO> pageFollowers(Long targetId, String cursor, int limit) {
        if (targetId == null || limit <= 0) {
            return List.of();
        }
        return pageFollowersFromDb(targetId, cursor, limit);
    }

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
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) {
                return null;
            }
            try {
                return new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
