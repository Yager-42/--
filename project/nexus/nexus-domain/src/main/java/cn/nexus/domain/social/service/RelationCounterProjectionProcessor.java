package cn.nexus.domain.social.service;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IClass2CounterProjectionStateRepository;
import cn.nexus.domain.social.adapter.repository.IClass2UserCounterRepairTaskRepository;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.Class2ProjectionAdvanceResult;
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
    private static final String ORDERING_METADATA_MISSING = "projection ordering metadata missing";
    private final IRelationRepository relationRepository;
    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IUserCounterService userCounterService;
    private final IClass2CounterProjectionStateRepository projectionStateRepository;
    private final IClass2UserCounterRepairTaskRepository repairTaskRepository;
    private final IContentRepository contentRepository;

    @Transactional(rollbackFor = Exception.class)
    public void process(RelationCounterProjectEvent event) {
        if (event == null) {
            return;
        }
        String type = normalize(event.getEventType());
        if ("POST".equals(type)) {
            applyPostProjection(event);
            return;
        }
        if (event.getSourceId() == null || event.getTargetId() == null) {
            return;
        }
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
            if (!advanceFollowProjectionIfOrdered(event, sourceId, targetId)) {
                return;
            }
            if (projectionStateRepository.advanceIfNewer(event.getProjectionKey(), "FOLLOW", event.getProjectionVersion())
                    == Class2ProjectionAdvanceResult.STALE) {
                return;
            }
            if (!isActiveFollow(sourceId, targetId)) {
                enqueueRepairBothUsers(sourceId, targetId, "follow active ordering uncertainty");
                return;
            }
            Long followerRowId = event.getRelationEventId() == null ? relationSeed(sourceId, targetId) : event.getRelationEventId();
            boolean changed = relationRepository.saveFollowerIfAbsent(followerRowId, targetId, sourceId, new java.util.Date());
            if (!changed) {
                enqueueRepairBothUsers(sourceId, targetId, "follow active missing follower transition");
            } else {
                userCounterService.applyClass2DeltaOnce(event.getEventId(), sourceId, UserCounterType.FOLLOWING, 1L);
                userCounterService.applyClass2DeltaOnce(event.getEventId(), targetId, UserCounterType.FOLLOWER, 1L);
            }
            try {
                relationAdjacencyCachePort.addFollowWithTtl(sourceId, targetId, System.currentTimeMillis(), ADJACENCY_CACHE_TTL_SECONDS);
            } catch (Exception ignored) {
                enqueueRepairBothUsers(sourceId, targetId, "follow projection side effect failed");
            }
            return;
        }
        if (!"UNFOLLOW".equals(status)) {
            return;
        }
        if (!advanceFollowProjectionIfOrdered(event, sourceId, targetId)) {
            return;
        }
        if (projectionStateRepository.advanceIfNewer(event.getProjectionKey(), "FOLLOW", event.getProjectionVersion())
                == Class2ProjectionAdvanceResult.STALE) {
            return;
        }
        if (isActiveFollow(sourceId, targetId)) {
            enqueueRepairBothUsers(sourceId, targetId, "follow unfollow ordering uncertainty");
            return;
        }
        boolean changed = relationRepository.deleteFollowerIfPresent(targetId, sourceId);
        if (!changed) {
            enqueueRepairBothUsers(sourceId, targetId, "follow unfollow missing follower transition");
        } else {
            userCounterService.applyClass2DeltaOnce(event.getEventId(), sourceId, UserCounterType.FOLLOWING, -1L);
            userCounterService.applyClass2DeltaOnce(event.getEventId(), targetId, UserCounterType.FOLLOWER, -1L);
        }
        try {
            relationAdjacencyCachePort.removeFollowWithTtl(sourceId, targetId, ADJACENCY_CACHE_TTL_SECONDS);
        } catch (Exception ignored) {
            enqueueRepairBothUsers(sourceId, targetId, "follow projection side effect failed");
        }
    }

    private void applyBlockProjection(RelationCounterProjectEvent event) {
        Long sourceId = event.getSourceId();
        Long targetId = event.getTargetId();
        try {
            relationAdjacencyCachePort.removeFollowWithTtl(sourceId, targetId, ADJACENCY_CACHE_TTL_SECONDS);
        } catch (Exception ignored) {
            enqueueRepairBothUsers(sourceId, targetId, "follow projection side effect failed");
        }
        try {
            relationAdjacencyCachePort.removeFollowWithTtl(targetId, sourceId, ADJACENCY_CACHE_TTL_SECONDS);
        } catch (Exception ignored) {
            enqueueRepairBothUsers(sourceId, targetId, "follow projection side effect failed");
        }
    }

    private void applyPostProjection(RelationCounterProjectEvent event) {
        Long authorId = event.getSourceId();
        if (authorId == null) {
            return;
        }
        if (!advancePostProjectionIfOrdered(event, authorId)) {
            return;
        }
        if (projectionStateRepository.advanceIfNewer(event.getProjectionKey(), "POST", event.getProjectionVersion())
                == Class2ProjectionAdvanceResult.STALE) {
            return;
        }
        String status = normalize(event.getStatus());
        ContentPostEntity post = contentRepository.findPostBypassCache(event.getTargetId());
        if ("PUBLISHED".equals(status)) {
            if (post != null && Integer.valueOf(2).equals(post.getStatus())) {
                userCounterService.applyClass2DeltaOnce(event.getEventId(), authorId, UserCounterType.POST, 1L);
                return;
            }
            repairTaskRepository.enqueue("USER_CLASS2", authorId, "post state uncertainty", "USER_CLASS2:" + authorId);
            return;
        }
        if ("UNPUBLISHED".equals(status) || "DELETED".equals(status)) {
            if (post == null || !Integer.valueOf(2).equals(post.getStatus())) {
                userCounterService.applyClass2DeltaOnce(event.getEventId(), authorId, UserCounterType.POST, -1L);
                return;
            }
            repairTaskRepository.enqueue("USER_CLASS2", authorId, "post state uncertainty", "USER_CLASS2:" + authorId);
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

    public void registerFailureRepair(RelationCounterProjectEvent event, String reason) {
        if (event == null) {
            return;
        }
        String type = normalize(event.getEventType());
        if ("POST".equals(type) && event.getSourceId() != null) {
            repairTaskRepository.enqueue("USER_CLASS2", event.getSourceId(), reason, "USER_CLASS2:" + event.getSourceId());
            return;
        }
        enqueueRepairBothUsers(event.getSourceId(), event.getTargetId(), reason);
    }

    private void enqueueRepairBothUsers(Long sourceId, Long targetId, String reason) {
        if (sourceId != null) {
            repairTaskRepository.enqueue("USER_CLASS2", sourceId, reason, "USER_CLASS2:" + sourceId);
        }
        if (targetId != null && !targetId.equals(sourceId)) {
            repairTaskRepository.enqueue("USER_CLASS2", targetId, reason, "USER_CLASS2:" + targetId);
        }
    }

    private boolean advanceFollowProjectionIfOrdered(RelationCounterProjectEvent event, Long sourceId, Long targetId) {
        if (event == null
                || event.getProjectionKey() == null
                || event.getProjectionKey().isBlank()
                || event.getProjectionVersion() == null
                || event.getProjectionVersion() < 0L) {
            enqueueRepairBothUsers(sourceId, targetId, ORDERING_METADATA_MISSING);
            return false;
        }
        return true;
    }

    private boolean advancePostProjectionIfOrdered(RelationCounterProjectEvent event, Long authorId) {
        if (event == null
                || event.getProjectionKey() == null
                || event.getProjectionKey().isBlank()
                || event.getProjectionVersion() == null
                || event.getProjectionVersion() < 0L) {
            repairTaskRepository.enqueue("USER_CLASS2", authorId, ORDERING_METADATA_MISSING, "USER_CLASS2:" + authorId);
            return false;
        }
        return true;
    }
}
