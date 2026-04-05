package cn.nexus.domain.social.service;

import cn.nexus.domain.counter.adapter.port.IUserCounterPort;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
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
    private static final long FOLLOW_LIMIT = 5000L;

    private final ISocialIdPort socialIdPort;
    private final IRelationRepository relationRepository;
    private final IRelationEventOutboxRepository relationEventOutboxRepository;
    private final IRelationPolicyPort relationPolicyPort;
    private final IRelationAdjacencyCachePort adjacencyCachePort;
    private final IUserCounterPort userCounterPort;

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
        // 关注上限走缓存计数，目的是避免每次关注前都去做高成本 COUNT(*)。
        if (userCounterPort.getCount(sourceId, UserCounterType.FOLLOWING) >= FOLLOW_LIMIT) {
            return FollowResultVO.builder().status("LIMIT_REACHED").build();
        }

        RelationEntity existFollow = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
        // 已经是 ACTIVE 就直接返回，保证重复点击不会制造重复关系和重复事件。
        if (existFollow != null && Integer.valueOf(STATUS_ACTIVE).equals(existFollow.getStatus())) {
            return FollowResultVO.builder().status("ACTIVE").build();
        }

        long relationId = socialIdPort.nextId();
        long nowMs = socialIdPort.now();
        Date followTime = new Date(nowMs);
        RelationEntity saved = RelationEntity.builder()
                .id(relationId)
                .sourceId(sourceId)
                .targetId(targetId)
                .relationType(RELATION_FOLLOW)
                .status(STATUS_ACTIVE)
                .groupId(0L)
                .version(0L)
                .createTime(followTime)
                .build();
        // 事务内只做三件最核心的事：写关系边、写粉丝倒排、写 outbox。
        relationRepository.saveRelation(saved);
        relationRepository.saveFollower(socialIdPort.nextId(), targetId, sourceId, followTime);

        long eventId = socialIdPort.nextId();
        relationEventOutboxRepository.save(eventId, "FOLLOW", buildFollowPayload(eventId, sourceId, targetId, "ACTIVE"));

        // 缓存和邻接门面一定要等事务提交后再碰，避免回滚时出现“库没成功，缓存先脏了”。
        afterCommit(() -> {
            adjacencyCachePort.addFollow(sourceId, targetId, nowMs);
            userCounterPort.increment(sourceId, UserCounterType.FOLLOWING, 1);
            userCounterPort.increment(targetId, UserCounterType.FOLLOWER, 1);
        });
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

        RelationEntity existFollow = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
        if (existFollow == null || !Integer.valueOf(STATUS_ACTIVE).equals(existFollow.getStatus())) {
            // 没关注也顺手清一次残留数据，这是幂等修复，不是额外业务。
            afterCommit(() -> {
                adjacencyCachePort.removeFollow(sourceId, targetId);
                userCounterPort.evict(sourceId, UserCounterType.FOLLOWING);
                userCounterPort.evict(targetId, UserCounterType.FOLLOWER);
            });
            relationRepository.deleteFollower(targetId, sourceId);
            return FollowResultVO.builder().status("NOT_FOLLOWING").build();
        }

        relationRepository.deleteRelation(sourceId, targetId, RELATION_FOLLOW);
        relationRepository.deleteFollower(targetId, sourceId);

        long eventId = socialIdPort.nextId();
        relationEventOutboxRepository.save(eventId, "FOLLOW", buildFollowPayload(eventId, sourceId, targetId, "UNFOLLOW"));

        // 写侧不精确删除 Feed inbox，而是把“取关立刻生效”的责任交给事件链路和读侧过滤。
        afterCommit(() -> {
            adjacencyCachePort.removeFollow(sourceId, targetId);
            userCounterPort.increment(sourceId, UserCounterType.FOLLOWING, -1);
            userCounterPort.increment(targetId, UserCounterType.FOLLOWER, -1);
        });
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
                userCounterPort.increment(sourceId, UserCounterType.FOLLOWING, -1);
                userCounterPort.increment(targetId, UserCounterType.FOLLOWER, -1);
            }
            if (reverseFollow) {
                userCounterPort.increment(targetId, UserCounterType.FOLLOWING, -1);
                userCounterPort.increment(sourceId, UserCounterType.FOLLOWER, -1);
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
            /**
             * 执行 afterCommit 逻辑。
             *
             */
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
