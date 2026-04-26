package cn.nexus.domain.social.service;

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

import java.util.Date;
import java.util.Objects;

/**
 * 关系领域写服务：负责关注、取关、拉黑这几条改边主链路。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
@Service
@RequiredArgsConstructor
public class RelationService implements IRelationService {

    private static final int RELATION_FOLLOW = 1;
    private static final int RELATION_BLOCK = 3;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private final ISocialIdPort socialIdPort;
    private final IRelationRepository relationRepository;
    private final IRelationEventOutboxRepository relationEventOutboxRepository;
    private final IRelationPolicyPort relationPolicyPort;

    /**
     * 建立关注关系。
     *
     * @param sourceId 发起关注的用户 ID，类型：{@link Long}
     * @param targetId 被关注用户 ID，类型：{@link Long}
     * @return 关注结果，类型：{@link FollowResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FollowResultVO follow(Long sourceId, Long targetId) {
        // 第一层先把脏请求挡掉，避免后面落库时出现“自己关注自己”或空 ID 这种无意义写入。
        if (invalidPair(sourceId, targetId)) {
            return FollowResultVO.builder().status("INVALID").build();
        }
        // block 检查必须在事务主写入前完成，否则会把不允许建立的边写进真相源。
        if (relationPolicyPort.isBlocked(sourceId, targetId) || blockedPair(sourceId, targetId)) {
            return FollowResultVO.builder().status("BLOCKED").build();
        }

        Date followTime = new Date(socialIdPort.now());
        RelationEntity locked = relationRepository.findRelationForUpdate(sourceId, targetId, RELATION_FOLLOW);
        long projectionVersion;
        if (locked == null) {
            long relationId = socialIdPort.nextId();
            relationRepository.saveRelation(RelationEntity.builder()
                    .id(relationId)
                    .sourceId(sourceId)
                    .targetId(targetId)
                    .relationType(RELATION_FOLLOW)
                    .status(STATUS_ACTIVE)
                    .groupId(0L)
                    .version(0L)
                    .createTime(followTime)
                    .build());
            projectionVersion = 0L;
        } else if (Integer.valueOf(STATUS_ACTIVE).equals(locked.getStatus())) {
            return FollowResultVO.builder().status("ACTIVE").build();
        } else {
            boolean activated = relationRepository.activateRelation(
                    sourceId,
                    targetId,
                    RELATION_FOLLOW,
                    locked.getVersion(),
                    followTime);
            if (!activated) {
                throw new IllegalStateException("follow relation CAS conflict");
            }
            RelationEntity refreshed = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
            projectionVersion = refreshed == null || refreshed.getVersion() == null ? 0L : refreshed.getVersion();
        }

        long eventId = socialIdPort.nextId();
        relationEventOutboxRepository.save(eventId,
                "FOLLOW",
                buildFollowPayload(
                        eventId,
                        sourceId,
                        targetId,
                        "ACTIVE",
                        "follow:" + sourceId + ":" + targetId,
                        projectionVersion));
        return FollowResultVO.builder().status("ACTIVE").build();
    }

    /**
     * 删除关注关系。
     *
     * @param sourceId 发起取关的用户 ID，类型：{@link Long}
     * @param targetId 被取关用户 ID，类型：{@link Long}
     * @return 取关结果，类型：{@link FollowResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FollowResultVO unfollow(Long sourceId, Long targetId) {
        if (invalidPair(sourceId, targetId)) {
            return FollowResultVO.builder().status("INVALID").build();
        }

        RelationEntity locked = relationRepository.findRelationForUpdate(sourceId, targetId, RELATION_FOLLOW);
        if (locked == null || !Integer.valueOf(STATUS_ACTIVE).equals(locked.getStatus())) {
            return FollowResultVO.builder().status("NOT_FOLLOWING").build();
        }
        boolean deactivated = relationRepository.deactivateRelation(
                sourceId,
                targetId,
                RELATION_FOLLOW,
                locked.getVersion(),
                STATUS_INACTIVE);
        if (!deactivated) {
            throw new IllegalStateException("unfollow relation CAS conflict");
        }
        RelationEntity refreshed = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
        long projectionVersion = refreshed == null || refreshed.getVersion() == null ? 0L : refreshed.getVersion();

        long eventId = socialIdPort.nextId();
        relationEventOutboxRepository.save(eventId,
                "FOLLOW",
                buildFollowPayload(
                        eventId,
                        sourceId,
                        targetId,
                        "UNFOLLOW",
                        "follow:" + sourceId + ":" + targetId,
                        projectionVersion));
        return FollowResultVO.builder().status("UNFOLLOWED").build();
    }

    /**
     * 建立拉黑关系，并清理双向关注边。
     *
     * @param sourceId 发起拉黑的用户 ID，类型：{@link Long}
     * @param targetId 被拉黑用户 ID，类型：{@link Long}
     * @return 拉黑执行结果，类型：{@link OperationResultVO}
     */
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

        // 拉黑不是单独加一条 block 边就结束，还要把双向 follow 关系和粉丝倒排一起清掉，
        // 否则后面的关注列表、粉丝列表、Feed 可见性都会继续看到旧关系。
        RelationEntity block = RelationEntity.builder()
                .id(socialIdPort.nextId())
                .sourceId(sourceId)
                .targetId(targetId)
                .relationType(RELATION_BLOCK)
                .status(STATUS_ACTIVE)
                .groupId(0L)
                .version(0L)
                .createTime(new Date(socialIdPort.now()))
                .build();
        relationRepository.saveRelation(block);
        if (forwardFollow) {
            deactivateFollowIfActive(sourceId, targetId);
        }
        if (reverseFollow) {
            deactivateFollowIfActive(targetId, sourceId);
        }

        long eventId = socialIdPort.nextId();
        relationEventOutboxRepository.save(eventId, "BLOCK", buildBlockPayload(eventId, sourceId, targetId));
        if (forwardFollow) {
            long followEventId = socialIdPort.nextId();
            long projectionVersion = relationVersion(sourceId, targetId);
            relationEventOutboxRepository.save(followEventId, "FOLLOW",
                    buildFollowPayload(
                            followEventId,
                            sourceId,
                            targetId,
                            "UNFOLLOW",
                            "follow:" + sourceId + ":" + targetId,
                            projectionVersion));
        }
        if (reverseFollow) {
            long followEventId = socialIdPort.nextId();
            long projectionVersion = relationVersion(targetId, sourceId);
            relationEventOutboxRepository.save(followEventId, "FOLLOW",
                    buildFollowPayload(
                            followEventId,
                            targetId,
                            sourceId,
                            "UNFOLLOW",
                            "follow:" + targetId + ":" + sourceId,
                            projectionVersion));
        }
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

    private String buildFollowPayload(Long eventId,
                                      Long sourceId,
                                      Long targetId,
                                      String status,
                                      String projectionKey,
                                      Long projectionVersion) {
        return "{"
                + "\"eventId\":" + eventId + ","
                + "\"sourceId\":" + sourceId + ","
                + "\"targetId\":" + targetId + ","
                + "\"status\":\"" + safe(status) + "\","
                + "\"projectionKey\":\"" + safe(projectionKey) + "\","
                + "\"projectionVersion\":" + (projectionVersion == null ? 0L : projectionVersion)
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

    private void deactivateFollowIfActive(Long sourceId, Long targetId) {
        RelationEntity locked = relationRepository.findRelationForUpdate(sourceId, targetId, RELATION_FOLLOW);
        if (locked == null || !Integer.valueOf(STATUS_ACTIVE).equals(locked.getStatus())) {
            return;
        }
        boolean updated = relationRepository.deactivateRelation(
                sourceId,
                targetId,
                RELATION_FOLLOW,
                locked.getVersion(),
                STATUS_INACTIVE);
        if (!updated) {
            throw new IllegalStateException("block cleanup relation CAS conflict");
        }
    }

    private long relationVersion(Long sourceId, Long targetId) {
        RelationEntity relation = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
        if (relation == null || relation.getVersion() == null) {
            return 0L;
        }
        return relation.getVersion();
    }
}
