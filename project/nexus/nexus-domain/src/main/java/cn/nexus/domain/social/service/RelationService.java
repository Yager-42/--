package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.port.IRelationCachePort;
import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IRelationEventOutboxRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.valobj.FollowResultVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * 关系领域服务实现。
 */
@Service
@RequiredArgsConstructor
public class RelationService implements IRelationService {

    private static final int RELATION_FOLLOW = 1;
    private static final int RELATION_BLOCK = 3;
    private static final int STATUS_ACTIVE = 1;
    private static final long FOLLOW_LIMIT = 5000L;

    private final ISocialIdPort socialIdPort;
    private final IRelationRepository relationRepository;
    private final IRelationEventOutboxRepository relationEventOutboxRepository;
    private final IRelationPolicyPort relationPolicyPort;
    private final IRelationAdjacencyCachePort adjacencyCachePort;
    private final IRelationCachePort relationCachePort;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FollowResultVO follow(Long sourceId, Long targetId) {
        if (invalidPair(sourceId, targetId)) {
            return FollowResultVO.builder().status("INVALID").build();
        }
        if (relationPolicyPort.isBlocked(sourceId, targetId) || blockedPair(sourceId, targetId)) {
            return FollowResultVO.builder().status("BLOCKED").build();
        }
        if (relationCachePort.getFollowingCount(sourceId) >= FOLLOW_LIMIT) {
            return FollowResultVO.builder().status("LIMIT_REACHED").build();
        }

        RelationEntity existFollow = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
        if (existFollow != null && Integer.valueOf(STATUS_ACTIVE).equals(existFollow.getStatus())) {
            return FollowResultVO.builder().status("ACTIVE").build();
        }

        long relationId = socialIdPort.nextId();
        long nowMs = socialIdPort.now();
        RelationEntity saved = RelationEntity.builder()
                .id(relationId)
                .sourceId(sourceId)
                .targetId(targetId)
                .relationType(RELATION_FOLLOW)
                .status(STATUS_ACTIVE)
                .groupId(0L)
                .version(0L)
                .build();
        relationRepository.saveRelation(saved);
        relationRepository.saveFollower(socialIdPort.nextId(), targetId, sourceId);

        long eventId = socialIdPort.nextId();
        relationEventOutboxRepository.save(eventId, "FOLLOW", buildFollowPayload(eventId, sourceId, targetId, "ACTIVE"));

        afterCommit(() -> {
            adjacencyCachePort.addFollow(sourceId, targetId, nowMs);
            relationCachePort.incrFollowing(sourceId, 1);
            relationCachePort.incrFollower(targetId, 1);
        });
        return FollowResultVO.builder().status("ACTIVE").build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FollowResultVO unfollow(Long sourceId, Long targetId) {
        if (invalidPair(sourceId, targetId)) {
            return FollowResultVO.builder().status("INVALID").build();
        }

        RelationEntity existFollow = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
        if (existFollow == null || !Integer.valueOf(STATUS_ACTIVE).equals(existFollow.getStatus())) {
            afterCommit(() -> {
                adjacencyCachePort.removeFollow(sourceId, targetId);
                relationCachePort.evict(sourceId);
                relationCachePort.evict(targetId);
            });
            relationRepository.deleteFollower(targetId, sourceId);
            return FollowResultVO.builder().status("NOT_FOLLOWING").build();
        }

        relationRepository.deleteRelation(sourceId, targetId, RELATION_FOLLOW);
        relationRepository.deleteFollower(targetId, sourceId);

        long eventId = socialIdPort.nextId();
        relationEventOutboxRepository.save(eventId, "FOLLOW", buildFollowPayload(eventId, sourceId, targetId, "UNFOLLOW"));

        afterCommit(() -> {
            adjacencyCachePort.removeFollow(sourceId, targetId);
            relationCachePort.incrFollowing(sourceId, -1);
            relationCachePort.incrFollower(targetId, -1);
        });
        return FollowResultVO.builder().status("UNFOLLOWED").build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO block(Long sourceId, Long targetId) {
        if (invalidPair(sourceId, targetId)) {
            return OperationResultVO.builder()
                    .success(false)
                    .status("INVALID")
                    .message("参数错误，未执行屏蔽")
                    .build();
        }

        boolean forwardFollow = isActiveFollow(sourceId, targetId);
        boolean reverseFollow = isActiveFollow(targetId, sourceId);

        RelationEntity block = RelationEntity.builder()
                .id(socialIdPort.nextId())
                .sourceId(sourceId)
                .targetId(targetId)
                .relationType(RELATION_BLOCK)
                .status(STATUS_ACTIVE)
                .groupId(0L)
                .version(0L)
                .build();
        relationRepository.saveRelation(block);
        relationRepository.deleteRelation(sourceId, targetId, RELATION_FOLLOW);
        relationRepository.deleteRelation(targetId, sourceId, RELATION_FOLLOW);
        relationRepository.deleteFollower(targetId, sourceId);
        relationRepository.deleteFollower(sourceId, targetId);

        long eventId = socialIdPort.nextId();
        relationEventOutboxRepository.save(eventId, "BLOCK", buildBlockPayload(eventId, sourceId, targetId));

        afterCommit(() -> {
            adjacencyCachePort.removeFollow(sourceId, targetId);
            adjacencyCachePort.removeFollow(targetId, sourceId);
            if (forwardFollow) {
                relationCachePort.incrFollowing(sourceId, -1);
                relationCachePort.incrFollower(targetId, -1);
            }
            if (reverseFollow) {
                relationCachePort.incrFollowing(targetId, -1);
                relationCachePort.incrFollower(sourceId, -1);
            }
        });
        return OperationResultVO.builder()
                .success(true)
                .id(eventId)
                .status("BLOCKED")
                .message("已屏蔽并清理关注关系")
                .build();
    }

    private boolean invalidPair(Long sourceId, Long targetId) {
        return sourceId == null || targetId == null || sourceId <= 0 || targetId <= 0 || Objects.equals(sourceId, targetId);
    }

    private boolean blockedPair(Long sourceId, Long targetId) {
        return relationRepository.findRelation(sourceId, targetId, RELATION_BLOCK) != null
                || relationRepository.findRelation(targetId, sourceId, RELATION_BLOCK) != null;
    }

    private boolean isActiveFollow(Long sourceId, Long targetId) {
        RelationEntity relation = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
        return relation != null && Integer.valueOf(STATUS_ACTIVE).equals(relation.getStatus());
    }

    private String buildFollowPayload(Long eventId, Long sourceId, Long targetId, String status) {
        return "{"
                + "\"eventId\":" + eventId + ","
                + "\"sourceId\":" + sourceId + ","
                + "\"targetId\":" + targetId + ","
                + "\"status\":\"" + safe(status) + "\""
                + "}";
    }

    private String buildBlockPayload(Long eventId, Long sourceId, Long targetId) {
        return "{"
                + "\"eventId\":" + eventId + ","
                + "\"sourceId\":" + sourceId + ","
                + "\"targetId\":" + targetId
                + "}";
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void afterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
