package cn.nexus.domain.social.service;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关系计数投影处理器：在持久幂等保护下维护 follower、邻接缓存和 ucnt。
 */
@Service
@RequiredArgsConstructor
public class RelationCounterProjectionProcessor {

    private static final int RELATION_FOLLOW = 1;
    private static final int STATUS_ACTIVE = 1;
    private static final long ADJACENCY_CACHE_TTL_SECONDS = 2L * 60L * 60L;
    private final IRelationRepository relationRepository;
    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IUserCounterService userCounterService;

    @Transactional(rollbackFor = Exception.class)
    public void process(RelationCounterProjectEvent event) {
        if (event == null || event.getSourceId() == null || event.getTargetId() == null) {
            return;
        }
        String type = normalize(event.getEventType());
        if ("FOLLOW".equals(type)) {
            applyFollowProjection(event);
            return;
        }
        if ("BLOCK".equals(type)) {
            applyBlockProjection(event);
        }
    }

    private void applyFollowProjection(RelationCounterProjectEvent event) {
        String status = normalize(event.getStatus());
        Long sourceId = event.getSourceId();
        Long targetId = event.getTargetId();
        if ("ACTIVE".equals(status)) {
            if (!isActiveFollow(sourceId, targetId)) {
                return;
            }
            Long followerRowId = event.getRelationEventId() == null ? relationSeed(sourceId, targetId) : event.getRelationEventId();
            boolean changed = relationRepository.saveFollowerIfAbsent(followerRowId, targetId, sourceId, new java.util.Date());
            if (!changed) {
                return;
            }
            relationAdjacencyCachePort.addFollowWithTtl(sourceId, targetId, System.currentTimeMillis(), ADJACENCY_CACHE_TTL_SECONDS);
            userCounterService.incrementFollowings(sourceId, 1);
            userCounterService.incrementFollowers(targetId, 1);
            return;
        }
        if (!"UNFOLLOW".equals(status)) {
            return;
        }
        if (isActiveFollow(sourceId, targetId)) {
            return;
        }
        boolean changed = relationRepository.deleteFollowerIfPresent(targetId, sourceId);
        if (!changed) {
            return;
        }
        relationAdjacencyCachePort.removeFollowWithTtl(sourceId, targetId, ADJACENCY_CACHE_TTL_SECONDS);
        userCounterService.incrementFollowings(sourceId, -1);
        userCounterService.incrementFollowers(targetId, -1);
    }

    private void applyBlockProjection(RelationCounterProjectEvent event) {
        Long sourceId = event.getSourceId();
        Long targetId = event.getTargetId();
        boolean forwardChanged = relationRepository.deleteFollowerIfPresent(targetId, sourceId);
        if (forwardChanged) {
            relationAdjacencyCachePort.removeFollowWithTtl(sourceId, targetId, ADJACENCY_CACHE_TTL_SECONDS);
        }
        boolean reverseChanged = relationRepository.deleteFollowerIfPresent(sourceId, targetId);
        if (reverseChanged) {
            relationAdjacencyCachePort.removeFollowWithTtl(targetId, sourceId, ADJACENCY_CACHE_TTL_SECONDS);
        }
    }

    private boolean isActiveFollow(Long sourceId, Long targetId) {
        cn.nexus.domain.social.model.entity.RelationEntity entity =
                relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
        return entity != null && Integer.valueOf(STATUS_ACTIVE).equals(entity.getStatus());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private long relationSeed(Long sourceId, Long targetId) {
        long s = sourceId == null ? 0L : sourceId;
        long t = targetId == null ? 0L : targetId;
        long mixed = (s << 32) ^ t;
        return mixed == 0L ? System.currentTimeMillis() : Math.abs(mixed);
    }
}
